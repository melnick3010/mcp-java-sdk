
package io.modelcontextprotocol.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import reactor.core.scheduler.Scheduler;
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

	// Dedicated scheduler for this transport instance to avoid global scheduler thread
	// leaks
	private final Scheduler dedicatedScheduler;

	// Readiness signal: completed when endpoint is discovered via SSE
	private volatile CompletableFuture<String> endpointReady = new CompletableFuture<>();

	// NB: lasciamo la reference globale per compatibilità, ma NON la usiamo nel
	// loop SSE
	private final AtomicReference<String> messageEndpoint = new AtomicReference<>();
	
	// Resources to close: SSE reader thread, response, and reader
	private volatile Thread sseReaderThread;
	private volatile CloseableHttpResponse sseResponse;
	private volatile BufferedReader sseReader;

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
		// Create a dedicated bounded elastic scheduler for this transport instance
		this.dedicatedScheduler = Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, "http-sse-client", 60, true);
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
			}
			else {
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
			// Store current thread for interruption during close
			sseReaderThread = Thread.currentThread();
			
			org.apache.http.client.methods.RequestBuilder rb = org.apache.http.client.methods.RequestBuilder.get()
					.setUri(uri).addHeader("Accept", "text/event-stream").addHeader("Cache-Control", "no-cache")
					.addHeader(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION);

			McpTransportContext transportContext = McpTransportContext.EMPTY;
			rb = reactor.core.publisher.Mono
					.from(this.httpRequestCustomizer.customize(rb, "GET", uri, (String) null, transportContext))
					.block();

			org.apache.http.client.methods.HttpUriRequest request = rb.build();
			try {
				sseResponse = httpClient.execute(request);
				sseReader = new BufferedReader(new InputStreamReader(sseResponse.getEntity().getContent()));
				
				logger.info("SSE connection established, waiting for endpoint discovery...");

				// Endpoint locale per questo stream SSE
				String endpointForThisStream = null;

				String line;
				while ((line = sseReader.readLine()) != null && !isClosing) {
					if (line.startsWith("event:")) {
						String eventType = line.substring(6).trim();

						// LOG DIAGNOSTICO: accumula una o più righe data:
						int dataLines = 0;
						StringBuilder dataBuilder = new StringBuilder();
						String next;
						sseReader.mark(8192);
						while ((next = sseReader.readLine()) != null) {
							if (next.startsWith("data:")) {
								if (dataLines > 0)
									dataBuilder.append('\n');
								dataBuilder.append(next.substring(5));
								dataLines++;
								continue;
							}
							sseReader.reset();
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
							
							// Complete the readiness signal
							if (!endpointReady.isDone()) {
								endpointReady.complete(data);
								logger.info("SSE ENDPOINT DISCOVERED and readiness signal completed: {}", endpointForThisStream);
							}
						}
						else if (MESSAGE_EVENT_TYPE.equals(eventType)) {
							try {
								JSONRPCMessage incoming = McpSchema.deserializeJsonRpcMessage(jsonMapper, data);
								String kind = (incoming instanceof McpSchema.JSONRPCRequest) ? "REQUEST"
										: (incoming instanceof McpSchema.JSONRPCResponse) ? "RESPONSE"
												: (incoming instanceof McpSchema.JSONRPCNotification) ? "NOTIFICATION"
														: "UNKNOWN";
								logger.info("SSE MESSAGE PARSED: kind={}", kind);

								Mono<JSONRPCMessage> out = handler.apply(Mono.just(incoming)).doOnNext(msg -> {
									String kindOut = (msg instanceof McpSchema.JSONRPCRequest) ? "REQUEST"
											: (msg instanceof McpSchema.JSONRPCResponse) ? "RESPONSE"
													: (msg instanceof McpSchema.JSONRPCNotification) ? "NOTIFICATION"
															: "UNKNOWN";

									Object idOut = null;
									if (msg instanceof McpSchema.JSONRPCRequest)
										idOut = ((McpSchema.JSONRPCRequest) msg).id();
									if (msg instanceof McpSchema.JSONRPCResponse)
										idOut = ((McpSchema.JSONRPCResponse) msg).id();

									logger.info("CLIENT HANDLER OUTPUT: kind={}, id={}", kindOut, idOut);
								});

								if (incoming instanceof McpSchema.JSONRPCRequest) {
									logger.info(
											"DISPATCH: kind=REQUEST -> WILL POST response to stream endpoint, thread={}",
											Thread.currentThread().getName());
									final String targetEndpoint = endpointForThisStream;

									out.flatMap(msg -> {
										Object idOut = null;
										if (msg instanceof McpSchema.JSONRPCRequest)
											idOut = ((McpSchema.JSONRPCRequest) msg).id();
										if (msg instanceof McpSchema.JSONRPCResponse)
											idOut = ((McpSchema.JSONRPCResponse) msg).id();
										logger.info(
												"CLIENT POST PREPARED: endpoint={}, id={}, thread={} - About to POST",
												targetEndpoint, idOut, Thread.currentThread().getName());
										return postToEndpoint(msg, targetEndpoint);
									}).doOnError(ex -> logger.error("Failed to handle/send response, thread={}: {}",
											Thread.currentThread().getName(), ex.getMessage(), ex))
											.onErrorResume(ex -> Mono.empty()).subscribe();

								}
								else {
									logger.info("DISPATCH: kind={} -> NO POST (handled locally), thread={}", kind,
											Thread.currentThread().getName());
									out.doOnError(ex -> logger.error("Failed to handle incoming message", ex))
											.onErrorResume(ex -> Mono.empty()).subscribe();
								}
							}
							catch (IOException e) {
								logger.error("Failed to parse SSE message", e);
							}
						}
					}
				}
			}
			catch (IOException e) {
				logger.error("Error during SSE connection", e);
			}
		}).subscribeOn(dedicatedScheduler).then();
	}

	// Helper: POST verso un endpoint esplicito (quello del corrente stream)

	private Mono<Void> postToEndpoint(JSONRPCMessage message, String endpoint) {
		return Mono.defer(() -> {
			if (endpoint == null || isClosing) {
				logger.error("CLIENT postToEndpoint: endpoint={}, isClosing={} - Cannot POST", endpoint, isClosing);
				return Mono.error(new McpTransportException("Message endpoint not available or transport closing"));
			}
			return Mono.fromCallable(() -> {
				String jsonBody = jsonMapper.writeValueAsString(message);
				URI postUri = Utils.resolveUri(this.baseUri, endpoint);

				Object msgId = null;
				if (message instanceof McpSchema.JSONRPCResponse) {
					msgId = ((McpSchema.JSONRPCResponse) message).id();
				}
				logger.info("CLIENT postToEndpoint: STARTING POST to endpoint={}, messageId={}, thread={}", endpoint,
						msgId, Thread.currentThread().getName());

				org.apache.http.client.methods.RequestBuilder rb = org.apache.http.client.methods.RequestBuilder.post()
						.setUri(postUri).addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
						.addHeader(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION)
						.setEntity(new StringEntity(jsonBody, "UTF-8"));

				McpTransportContext transportContext = McpTransportContext.EMPTY;
				rb = reactor.core.publisher.Mono
						.from(this.httpRequestCustomizer.customize(rb, "POST", postUri, jsonBody, transportContext))
						.block();

				org.apache.http.client.methods.HttpUriRequest request = rb.build();

				// --- NUOVO: cronometro per latenza POST ---
				final long t0 = System.nanoTime();

				try (CloseableHttpResponse response = httpClient.execute(request)) {
					int statusCode = response.getStatusLine().getStatusCode();
					if (statusCode < 200 || statusCode >= 300) {
						logger.error("CLIENT postToEndpoint: POST FAILED with status={}, messageId={}, thread={}",
								statusCode, msgId, Thread.currentThread().getName());
						throw new McpTransportException("Failed to send message. Status: " + statusCode);
					}
					long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
					logger.info("CLIENT POST SUCCESS: status={}, elapsedMs={}, messageId={}, thread={}", statusCode,
							elapsedMs, msgId, Thread.currentThread().getName());
				}
				return null;
			}).subscribeOn(dedicatedScheduler).then();
		});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return Mono.defer(() -> {
			// Check if transport is closing
			if (isClosing) {
				return Mono.error(new McpTransportException("Transport is closing"));
			}
			
			// Wait for endpoint to be ready with timeout
			try {
				String endpoint = endpointReady.get(30, TimeUnit.SECONDS);
				logger.debug("Endpoint ready for sendMessage: {}", endpoint);
				
				// Double-check closing state after waiting
				if (isClosing) {
					return Mono.error(new McpTransportException("Transport is closing"));
				}
				
				return sendToEndpoint(message, endpoint);
			}
			catch (TimeoutException e) {
				logger.error("Timeout waiting for endpoint discovery");
				return Mono.error(new McpTransportException("Endpoint discovery timeout after 30 seconds"));
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return Mono.error(new McpTransportException("Interrupted while waiting for endpoint"));
			}
			catch (Exception e) {
				return Mono.error(new McpTransportException("Failed to get endpoint: " + e.getMessage(), e));
			}
		});
	}
	
	private Mono<Void> sendToEndpoint(JSONRPCMessage message, String endpoint) {
		return Mono.defer(() -> {
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
			}).subscribeOn(dedicatedScheduler).then();
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			logger.info("Starting graceful close of SSE transport...");
			isClosing = true;
			
			// 1. Interrupt SSE reader thread to unblock readLine()
			if (sseReaderThread != null && sseReaderThread.isAlive()) {
				logger.info("Interrupting SSE reader thread...");
				sseReaderThread.interrupt();
			}
			
			// 2. Abort HTTP connection BEFORE closing streams to avoid blocking on chunked streams
			if (sseResponse != null) {
				try {
					logger.info("Aborting SSE HTTP response...");
					// Try to abort the response to close the underlying socket
					sseResponse.close();
					logger.debug("SSE response aborted");
				}
				catch (IOException e) {
					logger.debug("Error aborting SSE response (expected during shutdown)", e);
				}
			}
			
			// 3. Close HTTP client to terminate socket connections
			if (httpClient != null) {
				try {
					logger.info("Closing HTTP client to terminate connections...");
					httpClient.close();
					logger.info("HTTP client closed successfully");
				}
				catch (IOException e) {
					logger.warn("Error closing HTTP client", e);
				}
			}
			
			// 4. Wait for SSE thread to terminate (should be quick now that socket is closed)
			if (sseReaderThread != null && sseReaderThread.isAlive()) {
				try {
					logger.info("Waiting for SSE reader thread to terminate...");
					sseReaderThread.join(2000); // Reduced timeout since socket is closed
					if (sseReaderThread.isAlive()) {
						logger.warn("SSE reader thread did not terminate within 2 seconds, continuing anyway");
					} else {
						logger.info("SSE reader thread terminated successfully");
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					logger.warn("Interrupted while waiting for SSE thread termination");
				}
			}
			
			// 5. Now safely close remaining resources (should not block since socket is closed)
			closeResourcesSafely();
			
			// 6. Dispose the dedicated scheduler
			if (dedicatedScheduler != null && !dedicatedScheduler.isDisposed()) {
				logger.info("Disposing dedicated scheduler...");
				dedicatedScheduler.dispose();
				logger.info("Dedicated scheduler disposed successfully");
			}
			
			logger.info("Graceful close completed");
		});
	}
	
	private void closeResources() {
		// Close reader
		if (sseReader != null) {
			try {
				sseReader.close();
				logger.debug("SSE reader closed");
			}
			catch (IOException e) {
				logger.debug("Error closing SSE reader", e);
			}
		}
		
		// Close response
		if (sseResponse != null) {
			try {
				sseResponse.close();
				logger.debug("SSE response closed");
			}
			catch (IOException e) {
				logger.debug("Error closing SSE response", e);
			}
		}
	}
	
	private void closeResourcesSafely() {
		// Close reader safely (socket should already be closed)
		if (sseReader != null) {
			try {
				logger.debug("Closing SSE reader (socket already closed)...");
				sseReader.close();
				logger.debug("SSE reader closed safely");
			}
			catch (IOException e) {
				logger.debug("Error closing SSE reader (expected after socket close)", e);
			}
			finally {
				sseReader = null;
			}
		}
		
		// Response should already be closed, but ensure cleanup
		if (sseResponse != null) {
			try {
				logger.debug("Ensuring SSE response is closed...");
				sseResponse.close();
				logger.debug("SSE response cleanup completed");
			}
			catch (IOException e) {
				logger.debug("Error during SSE response cleanup (expected)", e);
			}
			finally {
				sseResponse = null;
			}
		}
	}
	
	/**
	 * Reset endpoint readiness for reconnection scenarios.
	 * This should be called when SSE connection is lost and needs to be re-established.
	 */
	private void resetEndpointReadiness() {
		if (!isClosing) {
			logger.info("Resetting endpoint readiness for potential reconnection");
			endpointReady = new CompletableFuture<>();
		}
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.jsonMapper.convertValue(data, typeRef);
	}

}
