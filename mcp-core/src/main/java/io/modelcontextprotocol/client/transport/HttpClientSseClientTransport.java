
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
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

	private final PoolingHttpClientConnectionManager connectionManager;

	private final McpJsonMapper jsonMapper;

	private final McpAsyncHttpClientRequestCustomizer httpRequestCustomizer;

	private volatile boolean isClosing = false;

	// Count of in-flight POST requests triggered by incoming REQUEST messages
	private final AtomicInteger inflightPosts = new AtomicInteger(0);

	// Map of outgoing request id -> method for correlation when responses arrive
	private final java.util.concurrent.ConcurrentMap<Object, String> outgoingRequestMethods = new java.util.concurrent.ConcurrentHashMap<>();

	private static final long WRITE_ERROR_GRACE_MS = 5000L;

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

	protected HttpClientSseClientTransport(CloseableHttpClient httpClient,
			PoolingHttpClientConnectionManager connectionManager, String baseUri, String sseEndpoint,
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
		this.connectionManager = connectionManager;
		this.httpRequestCustomizer = httpRequestCustomizer;
		// Create a dedicated bounded elastic scheduler for this transport instance with
		// daemon threads
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
			PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
			connManager.setMaxTotal(20);
			connManager.setDefaultMaxPerRoute(10);

			CloseableHttpClient httpClient = clientFactory != null ? clientFactory.get()
					: createPooledHttpClient(connManager);
			return new HttpClientSseClientTransport(httpClient, connManager, baseUri, sseEndpoint,
					jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper, httpRequestCustomizer);
		}

		private static CloseableHttpClient createPooledHttpClient(PoolingHttpClientConnectionManager connManager) {
			RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(3000)
					.setConnectionRequestTimeout(3000).setSocketTimeout(15000).setExpectContinueEnabled(false).build();

			return HttpClientBuilder.create().setConnectionManager(connManager).setDefaultRequestConfig(requestConfig)
					.build();
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

		private java.util.function.Supplier<CloseableHttpClient> clientFactory = () -> createPooledHttpClient();

		public Builder clientFactory(java.util.function.Supplier<CloseableHttpClient> factory) {
			this.clientFactory = (factory == null ? () -> createPooledHttpClient() : factory);
			return this;
		}

		/**
		 * Creates a pooled HTTP client optimized for SSE long-lived connections and
		 * concurrent POST requests.
		 *
		 * Configuration: - maxTotal = 20: Total connections across all routes -
		 * defaultMaxPerRoute = 10: Max connections per route (allows SSE + multiple
		 * POSTs) - connectTimeout = 3000ms: Socket connection timeout -
		 * connectionRequestTimeout = 3000ms: Timeout waiting for connection from pool -
		 * socketTimeout = 15000ms: Socket read timeout - Expect-Continue disabled:
		 * Reduces round-trips for POST requests
		 *
		 * This prevents connection pool contention when SSE long-lived connection shares
		 * the client with POST requests for ping responses.
		 */
		private static CloseableHttpClient createPooledHttpClient() {
			// Configure connection pool
			PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
			connectionManager.setMaxTotal(20);
			connectionManager.setDefaultMaxPerRoute(10);

			// Configure request timeouts
			RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(3000) // Connection
																							// establishment
																							// timeout
					.setConnectionRequestTimeout(3000) // Timeout waiting for connection
														// from pool
					.setSocketTimeout(15000) // Socket read timeout
					.setExpectContinueEnabled(false) // Disable Expect: 100-continue for
														// faster POSTs
					.build();

			return HttpClientBuilder.create().setConnectionManager(connectionManager)
					.setDefaultRequestConfig(requestConfig).build();
		}

	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		URI uri = Utils.resolveUri(this.baseUri, this.sseEndpoint);
		return Mono.fromRunnable(() -> {
			// Store current thread for interruption during close
			sseReaderThread = Thread.currentThread();

			// Configure request with socket timeout for interruptible reads
			RequestConfig sseConfig = RequestConfig.custom().setConnectTimeout(5000).setConnectionRequestTimeout(5000)
					.setSocketTimeout(1000) // Critical: 1s read timeout allows periodic
											// wake-up
					.build();

			org.apache.http.client.methods.RequestBuilder rb = org.apache.http.client.methods.RequestBuilder.get()
					.setUri(uri).setConfig(sseConfig) // Apply socket timeout config
					.addHeader("Accept", "text/event-stream").addHeader("Cache-Control", "no-cache")
					.addHeader(MCP_PROTOCOL_VERSION_HEADER_NAME, MCP_PROTOCOL_VERSION);

			McpTransportContext transportContext = McpTransportContext.EMPTY;
			rb = reactor.core.publisher.Mono
					.from(this.httpRequestCustomizer.customize(rb, "GET", uri, (String) null, transportContext))
					.block();

			org.apache.http.client.methods.HttpUriRequest request = rb.build();
			boolean sseClosedUnexpectedly = false;
			try {
				sseResponse = httpClient.execute(request);
				sseReader = new BufferedReader(new InputStreamReader(sseResponse.getEntity().getContent()));

				logger.info("SSE connection established, waiting for endpoint discovery...");

				// Endpoint locale per questo stream SSE
				String endpointForThisStream = null;

				String line;
				sseClosedUnexpectedly = false;
				while (!isClosing) {
					try {
						line = sseReader.readLine();
						if (line == null) {
							// Connection closed by server
							logger.info("CLIENT SSE CONNECTION CLOSED BY SERVER: thread={}",
									Thread.currentThread().getName());
							sseClosedUnexpectedly = true;
							break;
						}

						// Remove stored outgoing request method mapping when response has
						// been correlated.
					}
					catch (java.io.InterruptedIOException e) {
						// Periodic wake-up from socket timeout - check shutdown flag
						if (isClosing) {
							logger.debug("SSE reader exiting due to shutdown request");
							break;
						}
						// Continue reading
						continue;
					}
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
						// Structured event: client saw raw SSE data
						java.util.Map<String, Object> rawm = new java.util.HashMap<String, Object>();
						rawm.put("preview", preview);
						rawm.put("eventType", eventType);
						// Enrich raw event with sessionId / id / kind when possible
						if (ENDPOINT_EVENT_TYPE.equals(eventType)) {
							// endpoint contains URL with sessionId query param
							String sessionId = null;
							try {
								java.net.URI u = java.net.URI.create(data);
								String q = u.getQuery();
								if (q != null) {
									for (String part : q.split("&")) {
										if (part.startsWith("sessionId=")) {
											sessionId = part.substring("sessionId=".length());
											break;
										}
									}
								}
							}
							catch (Exception ex) {
								// ignore
							}
							if (sessionId != null) {
								rawm.put("sessionId", sessionId);
							}
						}
						else if (MESSAGE_EVENT_TYPE.equals(eventType)) {
							// Try to quickly extract id/kind from the JSON payload
							try {
								McpSchema.JSONRPCMessage mini = McpSchema.deserializeJsonRpcMessage(jsonMapper, data);
								MessageInfo mi = extractMessageInfo(mini);
								rawm.put("id", mi.id);
								rawm.put("kind", mi.kind);
							}
							catch (Exception ex) {
								// ignore - keep preview only
							}
						}
						io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "CLIENT", "SSE", "C_SSE_RAW", null,
								rawm, null, java.util.Collections.singletonMap("status", "PENDING"), null);

						if (ENDPOINT_EVENT_TYPE.equals(eventType)) {
							endpointForThisStream = data; // <-- per-STREAM
							messageEndpoint.set(data); // (compatibilità; non usato per
														// REQUEST SSE)

							// Complete the readiness signal
							if (!endpointReady.isDone()) {
								endpointReady.complete(data);
								logger.info("SSE ENDPOINT DISCOVERED and readiness signal completed: {}",
										endpointForThisStream);
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
								java.util.Map<String, Object> parsedm = new java.util.HashMap<String, Object>();
								Object parsedId = (incoming instanceof McpSchema.JSONRPCRequest)
										? ((McpSchema.JSONRPCRequest) incoming).id()
										: (incoming instanceof McpSchema.JSONRPCResponse)
												? ((McpSchema.JSONRPCResponse) incoming).id() : null;
								parsedm.put("id", parsedId);
								String parsedMethod = null;
								if (incoming instanceof McpSchema.JSONRPCRequest) {
									parsedMethod = ((McpSchema.JSONRPCRequest) incoming).method();
								}
								parsedm.put("method", parsedMethod);
								parsedm.put("kind", kind);
								// Correlation: for RESPONSE we can supply initiatorId
								// and, if known, method
								java.util.Map<String, Object> corr = null;
								if (incoming instanceof McpSchema.JSONRPCResponse) {
									corr = new java.util.HashMap<String, Object>();
									corr.put("initiatorId", parsedId);
									// try to recover method from outgoing map
									String origMethod = (parsedId == null) ? null
											: outgoingRequestMethods.get(parsedId);
									if (origMethod != null) {
										parsedm.put("method", origMethod);
									}
									corr.put("parentId", null);
									corr.put("seq", 0);
								}
								io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "CLIENT", "SSE",
										"C_SSE_PARSED", null, parsedm, corr,
										java.util.Collections.singletonMap("status", "PENDING"), null);

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
										java.util.Map<String, Object> postm = new java.util.HashMap<String, Object>();
										postm.put("id", idOut);
										postm.put("endpoint", targetEndpoint);
										java.util.Map<String, Object> corrPostStart = new java.util.HashMap<String, Object>();
										corrPostStart.put("initiatorId", idOut);
										corrPostStart.put("parentId", null);
										corrPostStart.put("seq", 0);
										io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "CLIENT", "HTTP",
												"C_POST_START", null, postm, corrPostStart,
												java.util.Collections.singletonMap("status", "PENDING"), null);
										return postToEndpoint(msg, targetEndpoint);
									}).doOnError(ex -> logger.error("Failed to handle/send response, thread={}: {}",
											Thread.currentThread().getName(), ex.getMessage(), ex))
											.onErrorResume(ex -> Mono.empty()).subscribe();

								}
								else {
									// Structured dispatch event: handled locally (include
									// parsed jsonrpc info)
									io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "CLIENT", "SSE",
											"C_DISPATCH", null, parsedm, null,
											java.util.Collections.singletonMap("route", "LOCAL"), null);
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
				if (isClosing) {
					logger.debug("SSE connection closed during shutdown (expected)");
				}
				else {
					logger.error("Error during SSE connection", e);
					sseClosedUnexpectedly = true;
				}
			}

			// If SSE closed unexpectedly, allow a short grace period for any
			// in-flight POSTs to complete before failing pending requests. This
			// reduces races where the server closes the SSE while a response is
			// actively being POSTed back.
			if (sseClosedUnexpectedly && !isClosing) {
				int inFlight = inflightPosts.get();
				if (inFlight > 0) {
					logger.warn("CLIENT detected SSE closed but {} POSTs in-flight; waiting {}ms before failing",
							inFlight, WRITE_ERROR_GRACE_MS);
					long waitUntil = System.currentTimeMillis() + WRITE_ERROR_GRACE_MS;
					while (inflightPosts.get() > 0 && System.currentTimeMillis() < waitUntil) {
						try {
							Thread.sleep(50);
						}
						catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							break;
						}
					}
					if (inflightPosts.get() == 0) {
						logger.info("CLIENT in-flight POSTs completed during grace; not failing pending requests");
					}
					else {
						logger.warn("CLIENT detected SSE closed and no in-flight POSTs; waiting {}ms before failing",
								WRITE_ERROR_GRACE_MS);
						long waitUntilNoInFlight = System.currentTimeMillis() + WRITE_ERROR_GRACE_MS;
						while (System.currentTimeMillis() < waitUntilNoInFlight && !isClosing) {
							try {
								Thread.sleep(50);
							}
							catch (InterruptedException ie) {
								Thread.currentThread().interrupt();
								break;
							}
						}
						throw new McpTransportException("SSE stream closed unexpectedly by server");
					}
				}
				else {
					// No POSTs in-flight at the moment of closure. Wait a short
					// grace window for the handler to start a POST (race window
					// where SSE closed just before client started posting).
					logger.warn(
							"CLIENT detected SSE closed and no in-flight POSTs; waiting up to {}ms for POSTS to start",
							WRITE_ERROR_GRACE_MS);
					long waitUntilStart = System.currentTimeMillis() + WRITE_ERROR_GRACE_MS;
					while (inflightPosts.get() == 0 && System.currentTimeMillis() < waitUntilStart) {
						try {
							Thread.sleep(50);
						}
						catch (InterruptedException ie) {
							Thread.currentThread().interrupt();
							break;
						}
					}
					if (inflightPosts.get() > 0) {
						logger.warn("CLIENT detected POSTs started during grace; waiting for completion up to {}ms",
								WRITE_ERROR_GRACE_MS);
						long waitUntilNoInFlight = System.currentTimeMillis() + WRITE_ERROR_GRACE_MS;
						while (System.currentTimeMillis() < waitUntilNoInFlight && inflightPosts.get() > 0
								&& !isClosing) {
							try {
								Thread.sleep(50);
							}
							catch (InterruptedException ie) {
								Thread.currentThread().interrupt();
								break;
							}
						}
						if (inflightPosts.get() == 0) {
							logger.info("CLIENT in-flight POSTs completed during grace; not failing pending requests");
						}
						else {
							throw new McpTransportException("SSE stream closed unexpectedly by server");
						}
					}
					else {
						throw new McpTransportException("SSE stream closed unexpectedly by server");
					}
				}
			}
		}).subscribeOn(dedicatedScheduler).then()
				.doOnError(e -> logger.error("SSE connect failed: {}", e.getMessage(), e));
	}

	// Helper: POST verso un endpoint esplicito (quello del corrente stream)

	private Mono<Void> postToEndpoint(JSONRPCMessage message, String endpoint) {
		return Mono.defer(() -> {
			if (endpoint == null) {
				logger.error("CLIENT postToEndpoint: endpoint={} - Cannot POST", endpoint);
				return Mono.error(new McpTransportException("Message endpoint not available"));
			}
			return Mono.fromCallable(() -> {
				inflightPosts.incrementAndGet();
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
					java.util.Map<String, Object> corrPostOk = new java.util.HashMap<String, Object>();
					corrPostOk.put("initiatorId", msgId);
					corrPostOk.put("parentId", null);
					corrPostOk.put("seq", 0);
					io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "CLIENT", "HTTP", "C_POST_OK", null,
							java.util.Collections.singletonMap("id", msgId), corrPostOk,
							new java.util.HashMap<String, Object>() {
								{
									put("status", "SUCCESS");
									put("statusCode", statusCode);
									put("elapsedMs", elapsedMs);
								}
							}, null);
				}
				finally {
					inflightPosts.decrementAndGet();
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
				// Record outgoing request method for correlation (if applicable)
				if (message instanceof McpSchema.JSONRPCRequest) {
					Object rid = ((McpSchema.JSONRPCRequest) message).id();
					String rm = ((McpSchema.JSONRPCRequest) message).method();
					if (rid != null && rm != null) {
						outgoingRequestMethods.put(rid, rm);
					}
				}
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
		if (isClosing) {
			// Idempotent: already closing - do not emit duplicate structured event
			logger.debug("closeGracefully() called but transport already closing; ignoring duplicate request");
			return Mono.empty();
		}

		return Mono.fromRunnable(() -> {
			logger.info("Starting graceful close of SSE transport...");

			// 1. Signal shutdown FIRST - this allows the read loop to exit on next
			// timeout
			isClosing = true;

			// 3. Allow a short grace period for any pending POSTs to start (non-blocking)
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			// Connection and client cleanup will be performed after waiting for SSE
			// reader thread

			// 6. Wait for SSE thread to terminate (should exit quickly due to socket
			// timeout + isClosing flag)
			if (sseReaderThread != null && sseReaderThread.isAlive()) {
				try {
					logger.info("Waiting for SSE reader thread to terminate...");
					// With SO_TIMEOUT=1s and scheduler disposed, thread should exit
					// within 1-2 seconds
					sseReaderThread.join(5000);
					if (sseReaderThread.isAlive()) {
						// Emit single structured timeout event including a compact stack
						// snapshot
						StackTraceElement[] stackTrace = sseReaderThread.getStackTrace();
						java.util.List<String> stackLines = new java.util.ArrayList<>();
						int limit = Math.min(stackTrace.length, 10);
						for (int i = 0; i < limit; i++) {
							stackLines.add(stackTrace[i].toString());
						}
						java.util.Map<String, Object> outcome = new java.util.HashMap<>();
						outcome.put("message", "reader did not terminate within 5s");
						outcome.put("stack", stackLines);
						io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "CLIENT", "SSE",
								"C_SSE_READER_TIMEOUT", null, null, null, outcome, null);
						logger.warn("SSE reader thread will be left running (daemon threads will not block JVM exit)");
						// stop further duplicate close sequences by ensuring isClosing
						// remains true
						return;
					}
					else {
						logger.info("SSE reader thread terminated successfully");
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					logger.warn("Interrupted while waiting for SSE thread termination");
				}
			}

			// 7. Close remaining resources safely (close SSE response/entity now)
			closeResourcesSafely();

			// 8. Shutdown connection manager to forcibly close all connections now that
			// POSTs had a chance to start
			if (connectionManager != null) {
				try {
					logger.info("Shutting down connection manager...");
					connectionManager.closeIdleConnections(0, TimeUnit.MILLISECONDS);
					connectionManager.shutdown();
					logger.info("Connection manager shut down successfully");
				}
				catch (Exception e) {
					logger.warn("Error shutting down connection manager", e);
				}
			}

			// 9. Close HTTP client
			if (httpClient != null) {
				try {
					logger.info("Closing HTTP client...");
					httpClient.close();
					logger.info("HTTP client closed successfully");
				}
				catch (IOException e) {
					logger.warn("Error closing HTTP client", e);
				}
			}

			// 10. Dispose dedicated scheduler last to allow pending POSTs to complete
			if (dedicatedScheduler != null && !dedicatedScheduler.isDisposed()) {
				logger.info("Disposing dedicated scheduler...");
				dedicatedScheduler.dispose();
				logger.info("Dedicated scheduler disposed successfully");
			}

			logger.info("Graceful close completed");
		});
	}

	private void dumpThreadStack(Thread thread) {
		StackTraceElement[] stackTrace = thread.getStackTrace();
		logger.debug("Stack trace (debug-only) for thread '{}' (State: {}):", thread.getName(), thread.getState());
		int limit = Math.min(stackTrace.length, 10);
		for (int i = 0; i < limit; i++) {
			logger.debug("  at {}", stackTrace[i]);
		}

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
	 * Reset endpoint readiness for reconnection scenarios. This should be called when SSE
	 * connection is lost and needs to be re-established.
	 */
	private void resetEndpointReadiness() {
		if (!isClosing) {
			logger.info("Resetting endpoint readiness for potential reconnection");
			endpointReady = new CompletableFuture<>();
		}
	}

	/**
	 * Remove stored outgoing request method mapping when a response has been correlated.
	 */
	public void clearOutgoingRequestMethod(Object id) {
		if (id != null) {
			this.outgoingRequestMethods.remove(id);
		}
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.jsonMapper.convertValue(data, typeRef);
	}

	private static final class MessageInfo {

		final Object id;

		final String kind;

		MessageInfo(Object id, String kind) {
			this.id = id;
			this.kind = kind;
		}

	}

	private MessageInfo extractMessageInfo(McpSchema.JSONRPCMessage msg) {
		Object id = null;
		if (msg instanceof McpSchema.JSONRPCRequest) {
			id = ((McpSchema.JSONRPCRequest) msg).id();
		}
		else if (msg instanceof McpSchema.JSONRPCResponse) {
			id = ((McpSchema.JSONRPCResponse) msg).id();
		}
		String kind = (msg instanceof McpSchema.JSONRPCRequest) ? "REQUEST" : (msg instanceof McpSchema.JSONRPCResponse)
				? "RESPONSE" : (msg instanceof McpSchema.JSONRPCNotification) ? "NOTIFICATION" : "UNKNOWN";
		return new MessageInfo(id, kind);
	}

}
