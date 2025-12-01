
package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSession;
import io.modelcontextprotocol.spec.DefaultMcpTransportSession;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;

public class HttpClientStreamableHttpTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(HttpClientStreamableHttpTransport.class);

	private static final String APPLICATION_JSON = "application/json";

	private static final String DEFAULT_ENDPOINT = "/mcp";

	private final CloseableHttpClient httpClient;

	private final McpJsonMapper jsonMapper;

	private final URI baseUri;

	private final String endpoint;

	private final boolean openConnectionOnStartup;

	private final boolean resumableStreams;

	private final McpAsyncHttpClientRequestCustomizer httpRequestCustomizer;

	private final AtomicReference<McpTransportSession<Disposable>> activeSession = new AtomicReference<>();

	private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handler = new AtomicReference<>();

	private final AtomicReference<Consumer<Throwable>> exceptionHandler = new AtomicReference<>();

	private final List<String> supportedProtocolVersions;

	private final String latestSupportedProtocolVersion;

	private HttpClientStreamableHttpTransport(McpJsonMapper jsonMapper, CloseableHttpClient httpClient, String baseUri,
			String endpoint, boolean resumableStreams, boolean openConnectionOnStartup,
			McpAsyncHttpClientRequestCustomizer httpRequestCustomizer, List<String> supportedProtocolVersions) {
		this.jsonMapper = jsonMapper;
		this.httpClient = httpClient;
		this.baseUri = URI.create(baseUri);
		this.endpoint = endpoint;
		this.resumableStreams = resumableStreams;
		this.openConnectionOnStartup = openConnectionOnStartup;
		this.activeSession.set(new DefaultMcpTransportSession(sessionId -> Mono.empty()));
		this.httpRequestCustomizer = httpRequestCustomizer;
		this.supportedProtocolVersions = Collections.unmodifiableList(supportedProtocolVersions);
		this.latestSupportedProtocolVersion = this.supportedProtocolVersions.stream()
			.sorted(Comparator.reverseOrder())
			.findFirst()
			.get();
	}

	@Override
	public List<String> protocolVersions() {
		return supportedProtocolVersions;
	}

	public static Builder builder(String baseUri) {
		return new Builder(baseUri);
	}

	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		this.handler.set(handler);
		if (this.openConnectionOnStartup) {
			logger.debug("Eagerly opening connection on startup");
			return reconnect().onErrorComplete(t -> {
				logger.warn("Eager connect failed ", t);
				return true;
			}).then();
		}
		return Mono.empty();
	}

	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		return Mono.fromCallable(() -> {
			String jsonBody = jsonMapper.writeValueAsString(message);
			HttpPost post = new HttpPost(Utils.resolveUri(this.baseUri, this.endpoint));
			post.setHeader("Content-Type", APPLICATION_JSON);
			post.setHeader("Accept", APPLICATION_JSON);
			post.setEntity(new StringEntity(jsonBody, "UTF-8"));
			try (CloseableHttpResponse response = httpClient.execute(post)) {
				int statusCode = response.getStatusLine().getStatusCode();
				if (statusCode >= 200 && statusCode < 300) {
					logger.debug("Message sent successfully: {}", message);
				}
				else {
					throw new McpTransportException("Failed to send message. Status: " + statusCode);
				}
			}
			return null;
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

	private Mono<Void> reconnect() {
		return Mono.fromCallable(() -> {
			HttpGet get = new HttpGet(Utils.resolveUri(this.baseUri, this.endpoint));
			get.setHeader("Accept", "text/event-stream");
			try (CloseableHttpResponse response = httpClient.execute(get)) {
				int statusCode = response.getStatusLine().getStatusCode();
				if (statusCode >= 200 && statusCode < 300) {
					logger.debug("Reconnected successfully");
				}
				else {
					throw new McpTransportException("Failed to reconnect. Status: " + statusCode);
				}
			}
			return null;
		}).subscribeOn(Schedulers.boundedElastic()).then();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.jsonMapper.convertValue(data, typeRef);
	}

	public static class Builder {

		private final String baseUri;

		private McpJsonMapper jsonMapper;

		private String endpoint = DEFAULT_ENDPOINT;

		private boolean resumableStreams = true;

		private boolean openConnectionOnStartup = false;

		private McpAsyncHttpClientRequestCustomizer httpRequestCustomizer = McpAsyncHttpClientRequestCustomizer.NOOP;

		private Duration connectTimeout = Duration.ofSeconds(10);

		private List<String> supportedProtocolVersions = java.util.Arrays.asList(ProtocolVersions.MCP_2024_11_05,
				ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2025_06_18);

		private Builder(String baseUri) {
			Assert.hasText(baseUri, "baseUri must not be empty");
			this.baseUri = baseUri;
		}

		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			this.jsonMapper = jsonMapper;
			return this;
		}

		public Builder endpoint(String endpoint) {
			this.endpoint = endpoint;
			return this;
		}

		public Builder resumableStreams(boolean resumableStreams) {
			this.resumableStreams = resumableStreams;
			return this;
		}

		public Builder openConnectionOnStartup(boolean openConnectionOnStartup) {
			this.openConnectionOnStartup = openConnectionOnStartup;
			return this;
		}

		public Builder asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer asyncHttpRequestCustomizer) {
			this.httpRequestCustomizer = asyncHttpRequestCustomizer;
			return this;
		}

		public Builder supportedProtocolVersions(List<String> supportedProtocolVersions) {
			this.supportedProtocolVersions = supportedProtocolVersions;
			return this;
		}

		// === Nuovo overload compatibile con i test (sync) ===

		public Builder httpRequestCustomizer(
				io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer syncCustomizer) {
			if (syncCustomizer == null) {
				this.httpRequestCustomizer = McpAsyncHttpClientRequestCustomizer.NOOP;
			}
			else {
				this.httpRequestCustomizer = (rb, method, uri, body, ctx) -> {
					// Invoca il customizer sync (modifica in-place)
					syncCustomizer.customize(rb, method, uri, body, ctx);
					// Restituisce il builder modificato
					return reactor.core.publisher.Mono.just(rb);
				};
			}
			return this;
		}

		public HttpClientStreamableHttpTransport build() {
			CloseableHttpClient httpClient = HttpClients.createDefault();
			return new HttpClientStreamableHttpTransport(jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper,
					httpClient, baseUri, endpoint, resumableStreams, openConnectionOnStartup, httpRequestCustomizer,
					supportedProtocolVersions);
		}

	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			logger.debug("Graceful close triggered");
			McpTransportSession<Disposable> currentSession = this.activeSession.get();
			this.activeSession.set(createClosedSession(currentSession));
			if (currentSession != null) {
				return Mono.from(currentSession.closeGracefully());
			}
			return Mono.empty();
		});
	}

	private McpTransportSession<Disposable> createClosedSession(McpTransportSession<Disposable> currentSession) {
		// Restituisce una sessione "chiusa" con onClose che non fa nulla
		return new DefaultMcpTransportSession(sessionId -> Mono.empty());
	}

}
