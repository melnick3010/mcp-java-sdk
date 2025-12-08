
package io.modelcontextprotocol.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class HttpClientSseClientTransport implements McpClientTransport {
	private static final String MCP_PROTOCOL_VERSION = ProtocolVersions.MCP_2024_11_05;
	private static final String MCP_PROTOCOL_VERSION_HEADER_NAME = "MCP-Protocol-Version";
	private static final Logger logger = LoggerFactory.getLogger(HttpClientSseClientTransport.class);

	private static final String MESSAGE_EVENT_TYPE = "message";
	private static final String ENDPOINT_EVENT_TYPE = "endpoint";
	private static final String DEFAULT_SSE_ENDPOINT = "/sse";

	private final URI baseUri;
	private final String sseEndpoint;
	private final CloseableHttpClient httpClient;
	private final McpJsonMapper jsonMapper;
	private final McpAsyncHttpClientRequestCustomizer httpRequestCustomizer;

	private volatile boolean isClosing = false;
	// NB: lasciamo la reference globale per compatibilità, ma NON la usiamo nel
	// loop SSE
	private final AtomicReference<String> messageEndpoint = new AtomicReference<>();

	protected HttpClientSseClientTransport(CloseableHttpClient httpClient, String baseUri, String sseEndpoint,
			McpJsonMapper jsonMapper, McpAsyncHttpClientRequestCustomizer httpRequestCustomizer) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.hasText(baseUri, "baseUri must not be empty");
		Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
		Assert.notNull(httpClient, "httpClient must not be null");
		Assert.notNull(httpRequestCustomizer, "httpRequestCustomizer must not be null");
		this.baseUri = URI.create(baseUri);
		this.sseEndpoint = sseEndpoint;
		this.jsonMapper = jsonMapper;
		this.httpClient = httpClient;
		this.httpRequestCustomizer = httpRequestCustomizer;
	}

	@Override
	public List<String> protocolVersions() {
		return Collections.singletonList(ProtocolVersions.MCP_2024_11_05);
	}

	public static Builder builder(String baseUri) {
		return new Builder(baseUri);
	}

	public static class Builder {
		private String baseUri;
		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;
		private McpJsonMapper jsonMapper;
		private McpAsyncHttpClientRequestCustomizer httpRequestCustomizer = McpAsyncHttpClientRequestCustomizer.NOOP;

		private Builder(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
		}

		public Builder sseEndpoint(String sseEndpoint) {
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			this.jsonMapper = jsonMapper;
			return this;
		}

		public Builder asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer asyncHttpRequestCustomizer) {
			this.httpRequestCustomizer = asyncHttpRequestCustomizer;
			return this;
		}

		public HttpClientSseClientTransport build() {
			CloseableHttpClient httpClient = clientFactory.get();
			return new HttpClientSseClientTransport(httpClient, baseUri, sseEndpoint,
					jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper, httpRequestCustomizer);
		}

		public Builder httpRequestCustomizer(
				io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer syncCustomizer) {
			if (syncCustomizer == null) {
				this.httpRequestCustomizer = McpAsyncHttpClientRequestCustomizer.NOOP;
			} else {
				this.httpRequestCustomizer = (rb, method, uri, body, ctx) -> {
					syncCustomizer.customize(rb, method, uri, body, ctx);
					return reactor.core.publisher.Mono.just(rb);
				};
			}
			return this;
		}

		private java.util.function.Supplier<CloseableHttpClient> clientFactory = org.apache.http.impl.client.HttpClients::createDefault;

		public Builder clientFactory(java.util.function.Supplier<CloseableHttpClient> factory) {
			this.clientFactory = (factory == null ? org.apache.http.impl.client.HttpClients::createDefault : factory);
			return this;
		}
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		URI uri = Utils.resolveUri(this.baseUri, this.sseEndpoint);
		return Mono.fromRunnable(() -> {
			org.apache.http.client.methods.RequestBuilder rb = org.apache.http.client.methods.RequestBuilder.get()
					.setUri(uri).addHeader("Accept", "text/event-stream").addHeader("Cache-Control", "no-cache")
					.addHeader(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION);

			McpTransportContext transportContext = McpTransportContext.EMPTY;
			rb = reactor.core.publisher.Mono
					.from(this.httpRequestCustomizer.customize(rb, "GET", uri, (String) null, transportContext))
					.block();

			org.apache.http.client.methods.HttpUriRequest request = rb.build();
			try (CloseableHttpResponse response = httpClient.execute(request);
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(response.getEntity().getContent()))) {

				// Endpoint locale per questo stream SSE
				String endpointForThisStream = null;

				String line;
				while ((line = reader.readLine()) != null && !isClosing) {
					if (line.startsWith("event:")) {
						String eventType = line.substring(6).trim();

						// LOG DIAGNOSTICO: accumula una o più righe data:
						int dataLines = 0;
						StringBuilder dataBuilder = new StringBuilder();
						String next;
						reader.mark(8192);
						while ((next = reader.readLine()) != null) {
							if (next.startsWith("data:")) {
								if (dataLines > 0)
									dataBuilder.append('\n');
								dataBuilder.append(next.substring(5));
								dataLines++;
								continue;
							}
							reader.reset();
							// riallinea la lettura sulla riga non-data
							line = next;
							break;
						}

						String data = dataBuilder.toString().trim();
						String preview = (data.length() <= 120) ? data : data.substring(0, 120) + "...";
						logger.info("SSE RAW EVENT: type={}, data_lines={}, payload_len={}, preview={}", eventType,
								dataLines, data.length(), preview);

						if (ENDPOINT_EVENT_TYPE.equals(eventType)) {
							endpointForThisStream = data; // <-- per-STREAM
							messageEndpoint.set(data); // (compatibilità; non usato per REQUEST SSE)
							logger.info("SSE ENDPOINT DISCOVERED (stream-local): {}", endpointForThisStream);
						} else if (MESSAGE_EVENT_TYPE.equals(eventType)) {
							try {
								JSONRPCMessage incoming = McpSchema.deserializeJsonRpcMessage(jsonMapper, data);
								String kind = (incoming instanceof McpSchema.JSONRPCRequest) ? "REQUEST"
										: (incoming instanceof McpSchema.JSONRPCResponse) ? "RESPONSE"
												: (incoming instanceof McpSchema.JSONRPCNotification) ? "NOTIFICATION"
														: "UNKNOWN";
								logger.info("SSE MESSAGE PARSED: kind={}", kind);

								Mono<JSONRPCMessage> out = handler.apply(Mono.just(incoming));

								if (incoming instanceof McpSchema.JSONRPCRequest) {
									logger.info("DISPATCH: kind=REQUEST -> WILL POST response to stream endpoint");
									final String targetEndpoint = endpointForThisStream;
									out.flatMap(msg -> postToEndpoint(msg, targetEndpoint))
											.doOnError(ex -> logger.error("Failed to handle/send response", ex))
											.onErrorResume(ex -> Mono.empty()).subscribe();
								} else {
									logger.info("DISPATCH: kind={} -> NO POST (handled locally)", kind);
									out.doOnError(ex -> logger.error("Failed to handle incoming message", ex))
											.onErrorResume(ex -> Mono.empty()).subscribe();
								}
							} catch (IOException e) {
								logger.error("Failed to parse SSE message", e);
							}
						}
					}
				}
			} catch (IOException e) {
				logger.error("Error during SSE connection", e);
			}
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

	// Helper: POST verso un endpoint esplicito (quello del corrente stream)
	private Mono<Void> postToEndpoint(JSONRPCMessage message, String endpoint) {
		return Mono.defer(() -> {
			if (endpoint == null || isClosing) {
				return Mono.error(new McpTransportException("Message endpoint not available or transport closing"));
			}
			return Mono.fromCallable(() -> {
				String jsonBody = jsonMapper.writeValueAsString(message);
				URI postUri = Utils.resolveUri(this.baseUri, endpoint);
				org.apache.http.client.methods.RequestBuilder rb = org.apache.http.client.methods.RequestBuilder.post()
						.setUri(postUri).addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
						.addHeader(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION)
						.setEntity(new StringEntity(jsonBody, "UTF-8"));

				McpTransportContext transportContext = McpTransportContext.EMPTY;
				rb = reactor.core.publisher.Mono
						.from(this.httpRequestCustomizer.customize(rb, "POST", postUri, jsonBody, transportContext))
						.block();

				org.apache.http.client.methods.HttpUriRequest request = rb.build();
				try (CloseableHttpResponse response = httpClient.execute(request)) {
					int statusCode = response.getStatusLine().getStatusCode();
					if (statusCode < 200 || statusCode >= 300) {
						throw new McpTransportException("Failed to send message. Status: " + statusCode);
					}
				}
				return null;
			}).subscribeOn(Schedulers.boundedElastic()).then();
		});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		// Manteniamo per compatibilità (es. notifiche esterne),
		// ma il loop SSE usa postToEndpoint(endpointForThisStream) per REQUEST in
		// arrivo
		return Mono.defer(() -> {
			String endpoint = messageEndpoint.get();
			if (endpoint == null || isClosing) {
				return Mono.error(new McpTransportException("Message endpoint not available or transport closing"));
			}
			return Mono.fromCallable(() -> {
				String jsonBody = jsonMapper.writeValueAsString(message);
				URI postUri = Utils.resolveUri(this.baseUri, endpoint);
				org.apache.http.client.methods.RequestBuilder rb = org.apache.http.client.methods.RequestBuilder.post()
						.setUri(postUri).addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
						.addHeader(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION)
						.setEntity(new StringEntity(jsonBody, "UTF-8"));

				McpTransportContext transportContext = McpTransportContext.EMPTY;
				rb = reactor.core.publisher.Mono
						.from(this.httpRequestCustomizer.customize(rb, "POST", postUri, jsonBody, transportContext))
						.block();

				org.apache.http.client.methods.HttpUriRequest request = rb.build();
				try (CloseableHttpResponse response = httpClient.execute(request)) {
					int statusCode = response.getStatusLine().getStatusCode();
					if (statusCode < 200 || statusCode >= 300) {
						throw new McpTransportException("Failed to send message. Status: " + statusCode);
					}
				}
				return null;
			}).subscribeOn(Schedulers.boundedElastic()).then();
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> isClosing = true);
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.jsonMapper.convertValue(data, typeRef);
	}
}
