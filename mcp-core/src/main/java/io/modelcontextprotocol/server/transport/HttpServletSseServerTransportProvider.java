/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.logging.McpLogging;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.KeepAliveScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * A Servlet-based implementation of the MCP HTTP with Server-Sent Events (SSE)
 * transport specification. This implementation provides similar functionality
 * to WebFluxSseServerTransportProvider but uses the traditional Servlet API
 * instead of WebFlux.
 *
 * <p>
 * The transport handles two types of endpoints:
 * <ul>
 * <li>SSE endpoint (/sse) - Establishes a long-lived connection for
 * server-to-client events</li>
 * <li>Message endpoint (configurable) - Handles client-to-server message
 * requests</li>
 * </ul>
 *
 * <p>
 *
 * // Keep an explicit mapping from AsyncContext -> sessionId to reliably //
 * detect which session an async context belongs to even when the // servlet
 * container triggers a completion path without exposing // request attributes.
 * Features:
 * <ul>
 * <li>Asynchronous message handling using Servlet 6.0 async support</li>
 * <li>Session management for multiple client connections</li>
 * <li>Graceful shutdown support</li>
 * <li>Error handling and response formatting</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see McpServerTransportProvider
 * @see HttpServlet
 */

@WebServlet(asyncSupported = true)
public class HttpServletSseServerTransportProvider extends HttpServlet implements McpServerTransportProvider {

	// Registry per sessioni SSE
	private final Map<String, SseSessionState> states = new ConcurrentHashMap<String, SseSessionState>();

	private final Map<String, AsyncContext> asyncs = new ConcurrentHashMap<String, AsyncContext>();

	private final Map<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<String, ScheduledFuture<?>>();

	// Flag per sessione: garantisce che S_ASYNC_COMPLETE venga loggato una sola
	// volta
	private final java.util.Map<String, java.util.concurrent.atomic.AtomicBoolean> asyncLogged = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>();

	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(HttpServletSseServerTransportProvider.class);

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";

	/**
	 * SSE-related header names and values
	 */
	private static final String CONTENT_TYPE_SSE = "text/event-stream";

	private static final String HEADER_CACHE_CONTROL = "Cache-Control";

	private static final String HEADER_CONNECTION = "Connection";

	private static final String HEADER_ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";

	private static final String HEADER_X_ACCEL_BUFFERING = "X-Accel-Buffering";

	private static final String NO_CACHE = "no-cache";

	private static final String KEEP_ALIVE = "keep-alive";

	private static final String ALLOW_ALL_ORIGINS = "*";

	private static final String NO_BUFFERING = "no";

	/**
	 * Default endpoint path for SSE connections
	 */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/**
	 * Event type for regular messages
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/**
	 * Event type for endpoint information
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	public static final String SESSION_ID = "sessionId";

	public static final String DEFAULT_BASE_URL = "";

	/**
	 * Timeout in milliseconds for async request processing
	 */
	private static final long ASYNC_TIMEOUT_MS = 60000;

	/**
	 * Grace period to wait for in-flight responses before forcefully closing SSE
	 */
	private static final long WRITE_ERROR_GRACE_MS = 5000L;

	/**
	 * JSON mapper for serialization/deserialization
	 */
	private final McpJsonMapper jsonMapper;

	/**
	 * Base URL for the server transport
	 */
	private final String baseUrl;

	/**
	 * The endpoint path for handling client messages
	 */
	private final String messageEndpoint;

	/**
	 * The endpoint path for handling SSE connections
	 */
	private final String sseEndpoint;

	/**
	 * Map of active client sessions, keyed by session ID
	 */
	private final Map<String, McpServerSession> sessions = new ConcurrentHashMap<>();

	// Map async contexts to session IDs to handle container-driven completions
	private final java.util.concurrent.ConcurrentMap<AsyncContext, String> asyncContextToSession = new java.util.concurrent.ConcurrentHashMap<>();

	private McpTransportContextExtractor<HttpServletRequest> contextExtractor;

	/**
	 * Flag indicating if the transport is in the process of shutting down
	 */
	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	/**
	 * Session factory for creating new sessions
	 */
	private McpServerSession.Factory sessionFactory;

	/**
	 * Keep-alive scheduler for managing session pings. Activated if
	 * keepAliveInterval is set. Disabled by default.
	 */
	private KeepAliveScheduler keepAliveScheduler;

	/**
	 * Dedicated scheduler for this transport instance to avoid global scheduler
	 * thread leaks. This scheduler is used for keep-alive operations and is
	 * properly disposed during shutdown.
	 */
	private final Scheduler dedicatedScheduler;

	private final AsyncCompleteLogger asyncLogger = new AsyncCompleteLogger() {
		@Override
		public void completeAndLogOnce(String sessionId, AsyncContext async, String caller) {
			// complete() protetto
			try {
				async.complete();
			} catch (Throwable ignore) {
			}

			// 1) Protezione: sessionId può essere null in alcuni rami
			if (sessionId == null) {
				Map<String, Object> meta = new java.util.HashMap<>();
				meta.put("caller", caller);
				Map<String, Object> extra = new java.util.HashMap<>();
				extra.put("meta", meta);
				// log strutturato senza sessionId
				io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "ASYNC", "S_ASYNC_COMPLETE", null,
						null, null, null, extra);
				return;
			}

			// log S_ASYNC_COMPLETE una sola volta
			java.util.concurrent.atomic.AtomicBoolean flag = asyncLogged.get(sessionId);
			if (flag == null) {
				flag = new java.util.concurrent.atomic.AtomicBoolean(false);
				asyncLogged.put(sessionId, flag);
			}
			if (flag.compareAndSet(false, true)) {
				java.util.Map<String, Object> meta = new java.util.HashMap<String, Object>();
				meta.put("caller", caller);
				java.util.Map<String, Object> extra = new java.util.HashMap<String, Object>();
				extra.put("meta", meta);

				io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "ASYNC", "S_ASYNC_COMPLETE",
						sessionId, null, null, null, extra);
			}
		}
	};

	/**
	 * Creates a new HttpServletSseServerTransportProvider instance with a custom
	 * SSE endpoint.
	 * 
	 * @param jsonMapper        The JSON object mapper to use for message
	 *                          serialization/deserialization
	 * @param baseUrl           The base URL for the server transport
	 * @param messageEndpoint   The endpoint path where clients will send their
	 *                          messages
	 * @param sseEndpoint       The endpoint path where clients will establish SSE
	 *                          connections
	 * @param keepAliveInterval The interval for keep-alive pings, or null to
	 *                          disable keep-alive functionality
	 * @param contextExtractor  The extractor for transport context from the
	 *                          request.
	 * @deprecated Use the builder {@link #builder()} instead for better
	 *             configuration options.
	 */
	private HttpServletSseServerTransportProvider(McpJsonMapper jsonMapper, String baseUrl, String messageEndpoint,
			String sseEndpoint, Duration keepAliveInterval,
			McpTransportContextExtractor<HttpServletRequest> contextExtractor) {

		Assert.notNull(jsonMapper, "JsonMapper must not be null");
		Assert.notNull(messageEndpoint, "messageEndpoint must not be null");
		Assert.notNull(sseEndpoint, "sseEndpoint must not be null");
		Assert.notNull(contextExtractor, "Context extractor must not be null");

		this.jsonMapper = jsonMapper;
		this.baseUrl = baseUrl;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
		this.contextExtractor = contextExtractor;

		// Create a dedicated bounded elastic scheduler for this transport instance
		this.dedicatedScheduler = Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, "http-servlet-sse", 60, true);

		if (keepAliveInterval != null) {

			this.keepAliveScheduler = KeepAliveScheduler
					.builder(() -> (isClosing.get()) ? Flux.empty() : Flux.fromIterable(sessions.values()))
					.scheduler(dedicatedScheduler) // Use dedicated scheduler instead of
													// global one
					.initialDelay(keepAliveInterval).interval(keepAliveInterval).build();

			this.keepAliveScheduler.start();
		}

	}

	@Override
	public List<String> protocolVersions() {
		return Collections.singletonList(ProtocolVersions.MCP_2024_11_05);
	}

	/**
	 * Sets the session factory for creating new sessions.
	 * 
	 * @param sessionFactory The session factory to use
	 */
	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a notification to all connected clients.
	 * 
	 * @param method The method name for the notification
	 * @param params The parameters for the notification
	 * @return A Mono that completes when the broadcast attempt is finished
	 */
	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values())
				.flatMap(session -> session.sendNotification(method, params).doOnError(
						e -> logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage()))
						.onErrorComplete())
				.then();
	}

	/**
	 * Handles GET requests to establish SSE connections.
	 * <p>
	 * This method sets up a new SSE connection when a client connects to the SSE
	 * endpoint. It configures the response headers for SSE, creates a new session,
	 * and sends the initial endpoint information to the client.
	 * 
	 * @param request  The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException      If an I/O error occurs
	 */

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (!validateSseRequest(request, response)) {
			return;
		}

		// Configure SSE headers
		configureSseHeaders(response);

		// Start async
		AsyncContext asyncContext = initializeAsyncContext(request);

		PrintWriter writer = response.getWriter();

		// Write an empty line and flush to immediately commit the body
		initializeAndFlushSseResponse(writer, response);

		String sessionId = createAndRegisterSession(asyncContext, writer);

		// Stato + listener
		SseSessionState state = new SseSessionState();
		states.put(sessionId, state);
		asyncs.put(sessionId, asyncContext);

		asyncContext.addListener(new SseAsyncListener(logger, sessionId, state, asyncLogger));

		// (opzionale) timeout iniziale, poi lo disattiviamo al graceful close
		asyncContext.setTimeout(30000L); // es. 30s

		// Annuncio endpoint (wired + strutturato)
		String endpointUrl = buildEndpointUrl(request, sessionId);
		sendEvent(writer, ENDPOINT_EVENT_TYPE, endpointUrl);
		flushSseEvent(writer, response);

		Map<String, Object> meta = new HashMap<String, Object>();
		meta.put("endpoint", endpointUrl);
		Map<String, Object> extra = new HashMap<String, Object>();
		extra.put("meta", meta);

		McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_ENDPOINT", sessionId, null, null, null, extra);

		// Avvio heartbeat (se previsto) e memorizzo il future
		ScheduledFuture<?> hb = startHeartbeat(sessionId, writer, response, state);
		if (hb != null) {
			heartbeats.put(sessionId, hb);
		}

	}

	/**
	 * Validates the SSE request by checking endpoint matching and server shutdown
	 * status.
	 * 
	 * @param request  The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @return true if validation succeeds, false otherwise
	 * @throws IOException If an I/O error occurs while sending error responses
	 */
	private boolean validateSseRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String requestURI = request.getRequestURI();
		// (diagnostic log)
		logger.debug("SSE doGet() requestURI={}", requestURI);

		if (!requestURI.endsWith(sseEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return false;
		}
		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return false;
		}
		return true;
	}

	/**
	 * Initializes and configures the async context for SSE connections.
	 * 
	 * @param request The HTTP servlet request
	 * @return The configured AsyncContext with timeout set to 0 (no timeout)
	 */
	private AsyncContext initializeAsyncContext(HttpServletRequest request) {
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);
		return asyncContext;
	}

	/**
	 * Constructs the full message endpoint URL by combining the base URL, message
	 * path, and the required session_id query parameter.
	 * 
	 * @param sessionId the unique session identifier
	 * @return the fully qualified endpoint URL as a string
	 */
	private String buildEndpointUrl(String sessionId) {
		// for WebMVC compatibility
		if (this.baseUrl.endsWith("/")) {
			return this.baseUrl.substring(0, this.baseUrl.length() - 1) + this.messageEndpoint + "?sessionId="
					+ sessionId;
		}
		return this.baseUrl + this.messageEndpoint + "?sessionId=" + sessionId;
	}

	/**
	 * Creates and registers a new MCP server session.
	 * 
	 * @param asyncContext The async context for the SSE connection
	 * @param writer       The PrintWriter for sending SSE events
	 * @return The session ID of the created session
	 */
	private String createAndRegisterSession(AsyncContext asyncContext, PrintWriter writer) {
		String sessionId = java.util.UUID.randomUUID().toString();

		// Session transport
		HttpServletMcpSessionTransport sessionTransport = new HttpServletMcpSessionTransport(sessionId, asyncContext,
				writer);

		// NOTE: sessionFactory must already be set by McpServer.async(...)
		McpServerSession session = sessionFactory.create(sessionTransport);
		this.sessions.put(sessionId, session);
		// Store sessionId on the async request so other handlers can map AsyncContext
		// ->
		// session
		try {
			((HttpServletRequest) asyncContext.getRequest()).setAttribute(SESSION_ID, sessionId);
			// Keep an explicit mapping from AsyncContext to sessionId as some servlet
			// containers complete the async context without preserving request
			// attributes. This allows us to find the session for safe completion.
			this.asyncContextToSession.put(asyncContext, sessionId);
		} catch (Exception e) {
			logger.debug("Unable to set sessionId attribute on async request: {}", e.getMessage());
		}

		return sessionId;
	}

	private String buildEndpointUrl(HttpServletRequest request, String sessionId) {
		// Derive scheme + host + port from SSE request
		String scheme = request.getScheme();
		String host = request.getServerName();
		int port = request.getServerPort();

		// Derive the base path of the current servlet/context
		// Example: if SSE is on /somePath/sse, and messageEndpoint = /mcp/message,
		// the final endpoint becomes /somePath/mcp/message
		String contextPath = request.getContextPath(); // e.g. "" or "/app"
		String servletPath = request.getServletPath(); // e.g. "/somePath/*"
		String ssePath = request.getRequestURI(); // e.g. "/somePath/sse"

		// Calculate the "directory" of the SSE path, removing the final "sse" segment
		int lastSlash = ssePath.lastIndexOf('/');
		String basePath = (lastSlash > 0) ? ssePath.substring(0, lastSlash) : "";
		// Now basePath is "/somePath"

		// Ensure messageEndpoint is normalized (with leading slash)
		String endpointPath = (this.messageEndpoint.startsWith("/")) ? this.messageEndpoint
				: "/" + this.messageEndpoint;

		// Build the final path maintaining the same basePath as SSE
		String fullPath = basePath + endpointPath;

		// Reconstruct the absolute URL (includes scheme/host/port)
		StringBuilder url = new StringBuilder();
		url.append(scheme).append("://").append(host);
		// Include port only if non-standard
		if (!("http".equalsIgnoreCase(scheme) && port == 80) && !("https".equalsIgnoreCase(scheme) && port == 443)) {
			url.append(':').append(port);
		}
		url.append(fullPath).append("?sessionId=").append(sessionId);
		return url.toString();
	}

	/**
	 * Configures SSE-specific response headers.
	 * 
	 * @param response The HTTP servlet response to configure
	 */
	private void configureSseHeaders(HttpServletResponse response) {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(CONTENT_TYPE_SSE);
		response.setCharacterEncoding(UTF_8);
		response.setHeader(HEADER_CACHE_CONTROL, NO_CACHE);
		response.setHeader(HEADER_CONNECTION, KEEP_ALIVE);
		response.setHeader(HEADER_ACCESS_CONTROL_ALLOW_ORIGIN, ALLOW_ALL_ORIGINS);
		response.setHeader(HEADER_X_ACCEL_BUFFERING, NO_BUFFERING);
	}

	/**
	 * Initializes and flushes the SSE response by writing an empty line and
	 * flushing buffers. This ensures the response body is committed immediately.
	 * 
	 * @param writer   The PrintWriter for the response
	 * @param response The HTTP servlet response
	 * @throws IOException If an I/O error occurs during flushing
	 */
	private void initializeAndFlushSseResponse(PrintWriter writer, HttpServletResponse response) throws IOException {
		writer.println();
		writer.flush();
		response.flushBuffer();
	}

	/**
	 * Flushes an SSE event to ensure it is sent to the client immediately.
	 * 
	 * @param writer   The PrintWriter for the response
	 * @param response The HTTP servlet response
	 * @throws IOException If an I/O error occurs during flushing
	 */
	private void flushSseEvent(PrintWriter writer, HttpServletResponse response) throws IOException {
		writer.flush();
		response.flushBuffer();
	}

	/**
	 * Validates the incoming POST request and extracts the session.
	 * 
	 * @param request  The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @return The session if validation succeeds, null otherwise
	 * @throws IOException If an I/O error occurs during validation
	 */
	private McpServerSession validatePostRequest(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return null;
		}

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(messageEndpoint)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return null;
		}

		// sessionId via query string
		String sessionId = request.getParameter("sessionId");
		if (sessionId == null || sessionId.isEmpty()) {
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing sessionId");
			return null;
		}

		// Get the session from the active sessions map; if not present check if the
		// session is in a short-lived draining state so that in-flight POSTs can
		// still be delivered.
		McpServerSession session = getSessionForPost(sessionId);
		if (session == null) {
			response.setContentType(APPLICATION_JSON);
			response.setCharacterEncoding(UTF_8);
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			String jsonError = jsonMapper.writeValueAsString(new McpError("Session not found: " + sessionId));
			PrintWriter writer = response.getWriter();
			writer.write(jsonError);
			writer.flush();
			logger.warn("SERVER doPost: sessionId NOT FOUND -> {}", sessionId);
			return null;
		}

		return session;
	}

	/**
	 * Extracts message type information from a JSON-RPC message.
	 * 
	 * @param message The JSON-RPC message
	 * @return MessageInfo containing the message kind and ID
	 */
	private MessageInfo extractMessageInfo(McpSchema.JSONRPCMessage message) {
		String kind;
		Object id;

		if (message instanceof McpSchema.JSONRPCRequest) {
			kind = "REQUEST";
			id = ((McpSchema.JSONRPCRequest) message).id();
		} else if (message instanceof McpSchema.JSONRPCResponse) {
			kind = "RESPONSE";
			id = ((McpSchema.JSONRPCResponse) message).id();
		} else if (message instanceof McpSchema.JSONRPCNotification) {
			kind = "NOTIFICATION";
			id = null;
		} else {
			kind = "UNKNOWN";
			id = null;
		}

		return new MessageInfo(kind, id);
	}

	/**
	 * Completes the async context with HTTP 200 OK status for responses with
	 * content.
	 * 
	 * @param asyncContext The async context to complete
	 * @param completed    Flag to prevent duplicate completion
	 * @param kind         The message kind for logging
	 * @param id           The message ID for logging
	 * @param startTime    The start time in nanoseconds for elapsed time
	 *                     calculation
	 */
	private void completeAsyncContextWithStatus(AsyncContext asyncContext, AtomicBoolean completed, String kind,
			Object id, long startTime) {
		if (!completed.compareAndSet(false, true)) {
			return;
		}

		long dtMs = (System.nanoTime() - startTime) / 1_000_000;
		logger.info("SERVER doPost ASYNC COMPLETED (with value): kind={}, id={}, elapsedMs={}, thread={}", kind, id,
				dtMs, Thread.currentThread().getName());

		HttpServletResponse asyncResponse = (HttpServletResponse) asyncContext.getResponse();
		if (asyncResponse != null) {
			asyncResponse.setStatus(HttpServletResponse.SC_OK);
		}
		// Determine session id for the async context to include in S_ASYNC_COMPLETE
		String sid = null;
		try {
			Object attr = asyncContext.getRequest().getAttribute(SESSION_ID);
			if (attr instanceof String) {
				sid = (String) attr;
			}
		} catch (Exception ex) {
			// ignore
		}
		if (sid == null) {
			sid = asyncContextToSession.get(asyncContext);
		}
		safeCompleteAsyncContext(asyncContext, sid);
	}

	/**
	 * Completes the async context without setting status for empty responses (SSE,
	 * notifications).
	 * 
	 * @param asyncContext The async context to complete
	 * @param completed    Flag to prevent duplicate completion
	 * @param kind         The message kind for logging
	 * @param id           The message ID for logging
	 * @param startTime    The start time in nanoseconds for elapsed time
	 *                     calculation
	 */

	private void completeAsyncContextEmpty(final AsyncContext asyncContext,
			final java.util.concurrent.atomic.AtomicBoolean completed, final String kind, final Object id,
			final long startTime) {
		// idempotenza locale del metodo
		if (!completed.compareAndSet(false, true)) {
			return;
		}

		final long dtMs = (System.nanoTime() - startTime) / 1_000_000L;
		// Evita log testuali ridondanti: se ti serve, usa DEBUG
		logger.debug("SERVER doPost ASYNC COMPLETED (empty): kind={}, id={}, elapsedMs={}, thread={}", kind, id, dtMs,
				Thread.currentThread().getName());

		// --- Ricava sessionId dall'AsyncContext ---
		String sid = null;
		try {
			Object attr = asyncContext.getRequest().getAttribute(SESSION_ID);
			if (attr instanceof String) {
				sid = (String) attr;
			}
		} catch (Exception ignored) {
		}
		if (sid == null) {
			// Se mantieni una mappa di associazione asyncContext -> sessionId
			sid = this.asyncContextToSession.get(asyncContext);
		}

		// --- Emissione S_REQ_COMPLETED strutturato (solo per kind=REQUEST) ---
		if ("REQUEST".equals(kind)) {
			// jsonrpc: id + kind; aggiungi method se disponibile come attributo
			final java.util.Map<String, Object> jrComplete = new java.util.HashMap<String, Object>();
			jrComplete.put("id", id);
			jrComplete.put("kind", "REQUEST");

			try {
				Object mAttr = asyncContext.getRequest().getAttribute("JSONRPC_METHOD");
				if (mAttr instanceof String) {
					jrComplete.put("method", (String) mAttr);
				}
			} catch (Exception ignored) {
			}

			// outcome: SUCCESS
			final java.util.Map<String, Object> outcome = new java.util.HashMap<String, Object>();
			outcome.put("status", "SUCCESS");

			// corr: pending count (se hai la sessione server registrata)
			int pending = 0;
			if (sid != null) {
				final McpServerSession s = this.sessions.get(sid);
				pending = (s == null ? 0 : s.pendingResponsesCount());
			}
			final java.util.Map<String, Object> corr = new java.util.HashMap<String, Object>();
			corr.put("pending", Integer.valueOf(pending));

			io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "HTTP", "S_REQ_COMPLETED", sid,
					jrComplete, corr, outcome, null);
		}

		// --- CHIUSURA + LOG UNICO (IDEMPOTENTE) ---
		// Usa l'helper centralizzato: completa l'AsyncContext e logga *una sola volta*
		// S_ASYNC_COMPLETE
		// Il caller include info utili per diagnosi (kind/id/delta).
		final String caller = "HttpServletSseServerTransportProvider.completeAsyncContextEmpty(kind=" + kind + ", id="
				+ String.valueOf(id) + ", elapsedMs=" + dtMs + ")";
		asyncLogger.completeAndLogOnce(sid, asyncContext, caller);

	}

	/**
	 * Safely completes an async context with consistent error handling and logging.
	 * 
	 * @param asyncContext The async context to complete
	 */
	private void safeCompleteAsyncContext(AsyncContext asyncContext) {
		safeCompleteAsyncContext(asyncContext, null);
	}

	private void safeCompleteAsyncContext(AsyncContext asyncContext, String sidOverride) {
		try {
			// Diagnostic: log caller stack to help track premature completions
			StackTraceElement[] stack = new Exception().getStackTrace();
			String callerClass = stack[1].getClassName();
			String callerMethod = stack[1].getMethodName();
			// Emit structured async-complete event (includes caller info and optional
			// session id). Prefer override when provided.
			String sidForEvent = sidOverride;
			if (sidForEvent == null) {
				try {
					Object attr = asyncContext.getRequest().getAttribute(SESSION_ID);
					if (attr instanceof String) {
						sidForEvent = (String) attr;
					}
				} catch (Exception ex) {
					// ignore
				}
			}
			// Fall back to asyncContext->session mapping when the request attribute
			// is not available (some servlet containers don't expose it).
			if (sidForEvent == null) {
				try {
					sidForEvent = asyncContextToSession.get(asyncContext);
				} catch (Exception ex) {
					// ignore
				}
			}

			// If this async context maps to a session with pending responses,
			// defer the completion briefly to give the client time to POST
			// in-flight responses. This avoids races where the container or
			// a write error closes the transport before responses arrive.
			// We do this regardless of the immediate caller method so that any
			// premature completion path is deferred while requests are pending.
			{
				try {
					String sid = null;
					try {
						Object attr = asyncContext.getRequest().getAttribute(SESSION_ID);
						if (attr instanceof String) {
							sid = (String) attr;
						}
					} catch (Exception ex) {
						// ignore - no request attribute available
					}
					if (sid == null) {
						// Fall back to the asyncContext->session mapping for cases when
						// the
						// servlet container does not expose the session id via request
						sid = asyncContextToSession.get(asyncContext);
					}
					if (sid != null) {
						McpServerSession s = sessions.get(sid);
						int pending = s == null ? 0 : s.pendingResponsesCount();
						if (pending > 0) {
							logger.warn(
									"safeCompleteAsyncContext deferring completion - pendingResponses={} for sessionId={}",
									pending, sid);
							// Schedule a deferred completion which will only execute if
							// pending still > 0
							long delay = WRITE_ERROR_GRACE_MS;
							try {
								long rt = s.getRequestTimeoutMillis();
								delay = Math.max(delay, rt);
							} catch (Exception ex) {
								// ignore - use default
							}
							final String sidFinal = sid;
							dedicatedScheduler.schedule(() -> {
								McpServerSession s2 = removeSession(sidFinal);
								if (s2 != null) {
									int p2 = s2.pendingResponsesCount();
									if (p2 > 0) {
										logger.warn(
												"safeCompleteAsyncContext deferred close executing (pending still {}): sessionId={}",
												p2, sidFinal);
										try {
											// Ensure transport is closed and async
											// completed
											s2.close();
										} catch (Exception ex) {
											logger.debug("Error during deferred session close for {}: {}", sidFinal,
													ex.getMessage());
										}
									} else {
										logger.info(
												"safeCompleteAsyncContext deferred close aborted (pending drained): sessionId={}",
												sidFinal);
									}
								}
							}, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
							return;
						}
						// No pending responses currently: still defer a short grace
						// window to
						// allow the client to start POSTs that may race with this
						// completion.
						// This reduces cases where the server completes the async context
						// immediately while the client is just about to initiate a POST.
						long delayShort = WRITE_ERROR_GRACE_MS;
						final String sidShort = sid;
						dedicatedScheduler.schedule(() -> {
							McpServerSession s2 = removeSession(sidShort);
							if (s2 != null) {
								int p2 = s2.pendingResponsesCount();
								if (p2 > 0) {
									logger.warn(
											"safeCompleteAsyncContext short-deferred close executing (pending still {}): sessionId={}",
											p2, sidShort);
									try {
										s2.close();
									} catch (Exception ex) {
										logger.debug("Error during short-deferred session close for {}: {}", sidShort,
												ex.getMessage());
									}
								} else {
									logger.info(
											"safeCompleteAsyncContext short-deferred close aborted (no posts started): sessionId={}",
											sidShort);
								}
							}
						}, delayShort, java.util.concurrent.TimeUnit.MILLISECONDS);
						return;
					}
				} catch (Exception e) {
					logger.debug("Error checking pending responses while deferring completion: {}", e.getMessage());
				}
			}

			asyncContext.complete();
		} catch (IllegalStateException e) {
			logger.debug("Async context already completed or timed out: {}", e.getMessage());
		} catch (Exception e) {
			logger.error("Error completing async context: {}", e.getMessage());
		}
	}

	/**
	 * Remove session by id and cleanup associated asyncContext mapping.
	 * 
	 * @param sessionId session id to remove
	 * @return the removed session or null if none
	 */
	private final java.util.concurrent.ConcurrentMap<String, McpServerSession> drainingSessions = new java.util.concurrent.ConcurrentHashMap<>();

	private McpServerSession removeSession(String sessionId) {
		McpServerSession s = this.sessions.remove(sessionId);
		if (s != null) {
			// Move to drainingSessions so in-flight POSTs can still be accepted for a
			// short grace window. Schedule final cleanup after a conservative delay.
			this.drainingSessions.put(sessionId, s);
			long delay = WRITE_ERROR_GRACE_MS;
			try {
				long rt = s.getRequestTimeoutMillis();
				delay = Math.max(delay, rt);
			} catch (Exception ex) {
				// ignore
			}
			final String sidFinal = sessionId;
			// Schedule final cleanup after the grace delay. We intentionally defer
			// removal of the asyncContext->session mapping so that any deferred
			// completion logic can still detect pending responses during the
			// grace window.
			dedicatedScheduler.schedule(() -> {
				try {
					this.asyncContextToSession.values().removeIf(v -> sidFinal.equals(v));
				} catch (Exception e) {
					logger.debug("Error cleaning asyncContext mapping for {}: {}", sidFinal, e.getMessage());
				}
				this.drainingSessions.remove(sidFinal);
				logger.debug("Finalized removal of drained session: {}", sidFinal);
			}, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
		return s;
	}

	/**
	 * Retrieve session by id checking active sessions first then draining sessions.
	 */
	private McpServerSession getSessionForPost(String sessionId) {
		McpServerSession s = this.sessions.get(sessionId);
		if (s == null) {
			s = this.drainingSessions.get(sessionId);
		}
		return s;
	}

	/**
	 * Close the session but defer the actual removal/async completion if there are
	 * pending responses. This helps avoid races where a transport is closed while
	 * requests are still in flight.
	 */
	private void closeSessionWithDrain(final String sessionId, final AsyncContext asyncContext) {
		// 1) Stato di sessione (idempotenza)
		// Assumi che esista una mappa 'states' <String, SseSessionState>, popolata in
		// doGet()
		final SseSessionState state = this.states.get(sessionId);
		if (state == null || asyncContext == null) {
			return;
		}

		// Se non è OPEN, stiamo già chiudendo/chiuso: evita doppi log/complete
		if (!state.beginGracefulClose()) {
			logger.debug("closeSessionWithDrain ignored (already closing/closed): sessionId={}", sessionId);
			return;
		}

		// 2) Disabilita subito il timeout dell'AsyncContext: previene onTimeout
		// post-graceful
		try {
			asyncContext.setTimeout(0L); // su molti container 0L = no-timeout; altrimenti
											// usa un valore molto grande
		} catch (Throwable ignore) {
		}

		// 3) Cancella heartbeat/scheduler (se gestiti) per evitare attività post-close
		// Assumi che esista una mappa 'heartbeats' <String, ScheduledFuture<?>>
		ScheduledFuture<?> hb = this.heartbeats != null ? this.heartbeats.remove(sessionId) : null;
		if (hb != null) {
			try {
				hb.cancel(true);
			} catch (Throwable ignore) {
			}
		}

		// 4) Verifica pending e applica drain/defer come già facevi
		McpServerSession sess = this.sessions.get(sessionId);
		int pending = (sess == null ? 0 : sess.pendingResponsesCount());

		if (pending > 0) {
			long delay = WRITE_ERROR_GRACE_MS;
			try {
				long rt = sess.getRequestTimeoutMillis();
				delay = Math.max(delay, rt);
			} catch (Exception ex) {
				// ignore
			}

			logger.warn("Deferring session close for sessionId={} for {}ms (pendingResponses={})", sessionId, delay,
					pending);

			final String sidFinal = sessionId;

			this.dedicatedScheduler.schedule(new Runnable() {
				@Override
				public void run() {
					McpServerSession s2 = removeSession(sidFinal);
					if (s2 != null) {
						int p2 = s2.pendingResponsesCount();
						if (p2 > 0) {
							logger.warn("Deferred session close executing (pending still {}): sessionId={}", p2,
									sidFinal);
							try {
								s2.close();
							} catch (Exception ex) {
								logger.debug("Error during deferred session close for {}: {}", sidFinal,
										ex.getMessage());
							}
						} else {
							logger.info("Deferred session close aborted (pending drained): sessionId={}", sidFinal);
						}
					}
					asyncLogger.completeAndLogOnce(sidFinal, asyncContext,
							"HttpServletSseServerTransportProvider.closeSessionWithDrain(deferred)");

					// Cleanup registri
					states.remove(sidFinal);
					// Se mantieni una mappa 'asyncs', rimuovi anche quella; qui abbiamo
					// asyncContext passato come argomento
					// asyncs.remove(sidFinal);
				}
			}, delay, java.util.concurrent.TimeUnit.MILLISECONDS);

		} else {
			// Nessun pending: chiudi subito
			removeSession(sessionId);

			// Complete + LOG UNA SOLA VOLTA
			asyncLogger.completeAndLogOnce(sessionId, asyncContext,
					"HttpServletSseServerTransportProvider.closeSessionWithDrain(immediate)");

			// Cleanup registri
			states.remove(sessionId);
			// asyncs.remove(sessionId);
		}
	}

	/**
	 * Handles errors during async message processing.
	 * 
	 * @param asyncContext The async context
	 * @param completed    Flag to prevent duplicate completion
	 * @param error        The error that occurred
	 */
	private void handleAsyncError(AsyncContext asyncContext, AtomicBoolean completed, Throwable error) {
		if (!completed.compareAndSet(false, true)) {
			logger.debug("Async context already completed, skipping error handler");
			return;
		}

		logger.error("Error processing message asynchronously: {}", error.getMessage());
		try {
			HttpServletResponse asyncResponse = (HttpServletResponse) asyncContext.getResponse();
			if (asyncResponse == null) {
				// Edge case: asyncResponse is null, cannot send error to client.
				// The async context will be completed via safeCompleteAsyncContext,
				// but the client will not receive specific error information.
				logger.error("Async response is null - unable to send error details to client");
			} else {
				McpError mcpError = new McpError(error.getMessage());
				asyncResponse.setContentType(APPLICATION_JSON);
				asyncResponse.setCharacterEncoding(UTF_8);
				asyncResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				String jsonError = jsonMapper.writeValueAsString(mcpError);
				PrintWriter writer = asyncResponse.getWriter();
				writer.write(jsonError);
				writer.flush();
			}
		} catch (IOException ex) {
			logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex);
		} finally {
			safeCompleteAsyncContext(asyncContext);
		}
	}

	/**
	 * Handles POST requests for client messages.
	 * <p>
	 * This method processes incoming messages from clients, routes them through the
	 * session handler, and sends back the appropriate response. It handles error
	 * cases and formats error responses according to the MCP specification.
	 * 
	 * @param request  The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException      If an I/O error occurs
	 */

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		// Validate request and get session
		McpServerSession session = validatePostRequest(request, response);
		if (session == null) {
			return;
		}

		String sessionId = request.getParameter("sessionId");

		// Read request body
		BufferedReader reader = request.getReader();
		StringBuilder body = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			body.append(line);
		}

		// Extract transport context
		final McpTransportContext transportContext = this.contextExtractor.extract(request);
		// Deserialize message
		McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body.toString());

		final MessageInfo messageInfo = extractMessageInfo(message);
		logger.info("SERVER doPost: kind={}, id={}, sessionId={}, uri={}, thread={}", messageInfo.kind, messageInfo.id,
				sessionId, request.getRequestURI(), Thread.currentThread().getName());
		// Structured server event: HTTP request received
		java.util.Map<String, Object> jr = new java.util.HashMap<String, Object>();
		jr.put("id", messageInfo.id);
		// jsonrpc.method should be the actual RPC method name when available
		if (message instanceof McpSchema.JSONRPCRequest) {
			jr.put("method", ((McpSchema.JSONRPCRequest) message).method());
		} else if (message instanceof McpSchema.JSONRPCNotification) {
			jr.put("method", ((McpSchema.JSONRPCNotification) message).method());
		} else {
			jr.put("method", null);
		}
		jr.put("kind", messageInfo.kind);
		java.util.Map<String, Object> outm = new java.util.HashMap<String, Object>();
		outm.put("status", "PENDING");
		io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "HTTP", "S_RECV_REQ_HTTP", sessionId, jr,
				null, outm, null);

		// Emit specific structured event for RESPONSE HTTP POST
		if ("RESPONSE".equals(messageInfo.kind)) {
			java.util.Map<String, Object> jr2 = new java.util.HashMap<String, Object>();
			jr2.put("id", messageInfo.id);
			// Responses don't carry method name; leave null
			jr2.put("method", null);
			jr2.put("kind", messageInfo.kind);
			java.util.Map<String, Object> outm2 = new java.util.HashMap<String, Object>();
			outm2.put("status", "PENDING");
			io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "HTTP", "S_RECV_RESP_HTTP", sessionId,
					jr2, null, outm2, null);
		}

// Dopo aver deserializzato 'message' e prima di 'final AsyncContext asyncContext = request.startAsync();'
		if (message instanceof McpSchema.JSONRPCRequest) {
			request.setAttribute("JSONRPC_METHOD", ((McpSchema.JSONRPCRequest) message).method());
		} else if (message instanceof McpSchema.JSONRPCNotification) {
			request.setAttribute("JSONRPC_METHOD", ((McpSchema.JSONRPCNotification) message).method());
		} else {
			request.setAttribute("JSONRPC_METHOD", null);
		}

		// Use async processing for all message types for consistency and better
		// resource
		// utilization
		// This prevents blocking the servlet thread and improves performance under high
		// load
		final AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(ASYNC_TIMEOUT_MS);

		logger.info("SERVER doPost: Starting ASYNC processing for kind={}, id={}, thread={}", messageInfo.kind,
				messageInfo.id, Thread.currentThread().getName());

		// IMPORTANT: AsyncListener is intentionally NOT added here to prevent race
		// conditions.
		//
		// Problem: The AsyncListener's onTimeout/onError handlers would dispose the
		// subscription
		// after the async context times out or encounters an error. However, for SSE
		// responses,
		// the subscription completes successfully and the async context is completed in
		// the
		// success handler (line ~459). The AsyncListener's lifecycle doesn't align with
		// the SSE
		// response lifecycle, creating a race condition where:
		// 1. SSE response is sent successfully
		// 2. Async context is completed (line ~459)
		// 3. AsyncListener.onTimeout/onError fires AFTER successful completion
		// 4. Subscription is incorrectly disposed despite successful response
		//
		// Solution: Resource cleanup is handled by the Reactive subscription itself:
		// - Success handler completes the async context immediately after SSE response
		// - Error handler completes the async context and sends error response
		// - Reactive timeout (line ~447) handles long-running operations
		// - No additional AsyncListener-based cleanup is needed or desired
		//
		// Alternative mechanisms ensure proper resource cleanup without race
		// conditions:
		// - Reactive timeout (55 seconds) shorter than async timeout (60 seconds)
		// - AtomicBoolean 'completed' flag prevents duplicate completion attempts
		// - Try-catch blocks handle IllegalStateException from already-completed
		// contexts

		final long t0 = System.nanoTime();
		final AtomicBoolean completed = new AtomicBoolean(false);

		session.handle(message).timeout(Duration.ofSeconds(55)) // Timeout
																// shorter
																// than
																// async
																// context
																// timeout
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)).subscribe(v -> {
					// Success handler - complete async context when Mono emits a value
					// (for responses with content)
					if ("REQUEST".equals(messageInfo.kind)) {
						java.util.Map<String, Object> jrComplete = new java.util.HashMap<String, Object>();
						jrComplete.put("id", messageInfo.id);
						jrComplete.put("kind", "REQUEST");
						java.util.Map<String, Object> outcome = java.util.Collections.singletonMap("status", "SUCCESS");
						java.util.Map<String, Object> extra = java.util.Collections.singletonMap("pending",
								Integer.valueOf(session.pendingResponsesCount()));
						io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "HTTP", "S_REQ_COMPLETED",
								sessionId, jrComplete, null, outcome, extra);
					}
					completeAsyncContextWithStatus(asyncContext, completed, messageInfo.kind, messageInfo.id, t0);
				}, error -> {
					// Error handler
					handleAsyncError(asyncContext, completed, error);
				}, () -> {
					// Completion handler for empty Mono (SSE responses, notifications)
					completeAsyncContextEmpty(asyncContext, completed, messageInfo.kind, messageInfo.id, t0);
				});
	}

	/**
	 * Initiates a graceful shutdown of the transport.
	 * <p>
	 * This method marks the transport as closing and closes all active client
	 * sessions. New connection attempts will be rejected during shutdown.
	 * 
	 * @return A Mono that completes when all sessions have been closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		isClosing.set(true);
		logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values()).flatMap(McpServerSession::closeGracefully).then().doOnSuccess(v -> {
			sessions.clear();
			logger.debug("Graceful shutdown completed");

			// Stop keep-alive scheduler if active
			if (this.keepAliveScheduler != null) {
				this.keepAliveScheduler.stop();
			}

			// Dispose dedicated scheduler to properly cleanup threads
			if (this.dedicatedScheduler != null && !this.dedicatedScheduler.isDisposed()) {
				this.dedicatedScheduler.dispose();
				logger.debug("Dedicated scheduler disposed");
			}
		});
	}

	/**
	 * Sends an SSE event to a client.
	 * 
	 * @param writer    The writer to send the event through
	 * @param eventType The type of event (message or endpoint)
	 * @param data      The event data
	 * @throws IOException If an error occurs while writing the event
	 */
	private void sendEvent(PrintWriter writer, String eventType, String data) throws IOException {
		writer.write("event: " + eventType + "\n");
		writer.write("data: " + data + "\n\n");
		writer.flush();

		if (writer.checkError()) {
			throw new IOException("Client disconnected");
		}
	}

	/**
	 * Cleans up resources when the servlet is being destroyed.
	 * <p>
	 * This method ensures a graceful shutdown by closing all client connections
	 * before calling the parent's destroy method.
	 */
	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

	/**
	 * Implementation of McpServerTransport for HttpServlet SSE sessions. This class
	 * handles the transport-level communication for a specific client session.
	 */
	private class HttpServletMcpSessionTransport implements McpServerTransport {

		private final String sessionId;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		private volatile boolean connectionClosed = false;

		// Heartbeat task disposable for periodic SSE comments
		private volatile Disposable heartbeat;

		// Timestamp of first observed write error; used to apply a short grace period
		private volatile Long lastWriteErrorAt;

		/**
		 * Creates a new session transport with the specified ID and SSE writer.
		 * 
		 * @param sessionId    The unique identifier for this session
		 * @param asyncContext The async context for the session
		 * @param writer       The writer for sending server events to the client
		 */
		HttpServletMcpSessionTransport(String sessionId, AsyncContext asyncContext, PrintWriter writer) {
			this.sessionId = sessionId;
			this.asyncContext = asyncContext;
			this.writer = writer;
			// Attach AsyncListener to trace lifecycle events and understand who/why
			// closes the stream
			this.asyncContext.addListener(new AsyncListener() {
				@Override
				public void onComplete(AsyncEvent event) throws IOException {
					logger.info("SERVER SSE AsyncListener.onComplete: sessionId={}, thread={}", sessionId,
							Thread.currentThread().getName());
					disposeHeartbeat();
				}

				@Override
				public void onTimeout(AsyncEvent event) throws IOException {
					logger.warn("SERVER SSE AsyncListener.onTimeout: sessionId={}, thread={}", sessionId,
							Thread.currentThread().getName());
					// If there are pending responses, defer closing briefly to allow the
					// client to POST them. This reduces races where the transport is
					// completed immediately while responses are in flight.
					McpServerSession sess = sessions.get(sessionId);
					int pending = sess == null ? 0 : sess.pendingResponsesCount();
					if (pending > 0) {
						logger.warn(
								"SERVER SSE AsyncListener.onTimeout: sessionId={}, pendingResponses={}, deferring close",
								sessionId, pending);
						lastWriteErrorAt = System.currentTimeMillis();
						connectionClosed = true;
						java.util.Map<String, Object> pendingm = new java.util.HashMap<String, Object>();
						pendingm.put("pending", pending);
						java.util.Map<String, Object> statusm = new java.util.HashMap<String, Object>();
						statusm.put("status", "ERROR");
						statusm.put("reason", "timeout");
						statusm.put("cause", "timeout");
						// Put correlation info in 'corr' and status in 'outcome'
						io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_CLOSED",
								sessionId, null, pendingm, statusm, null);
						closeSessionWithDrain(sessionId, asyncContext);
						disposeHeartbeat();
					} else {
						connectionClosed = true;
						// Closed with no pending: put pending in corr, status in outcome
						io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_CLOSED",
								sessionId, null, java.util.Collections.singletonMap("pending", pending),
								java.util.Collections.singletonMap("status", "CLOSED"), null);
						closeSessionWithDrain(sessionId, asyncContext);
						disposeHeartbeat();
					}
				}

				@Override
				public void onError(AsyncEvent event) throws IOException {
					logger.warn("SERVER SSE AsyncListener.onError: sessionId={}, error={}, thread={}", sessionId,
							event.getThrowable() == null ? "<null>" : event.getThrowable().getMessage(),
							Thread.currentThread().getName());
					// Defer closing if there are pending responses, similar to onTimeout
					McpServerSession sessErr = sessions.get(sessionId);
					int pendingErr = sessErr == null ? 0 : sessErr.pendingResponsesCount();
					if (pendingErr > 0) {
						logger.warn(
								"SERVER SSE AsyncListener.onError: sessionId={}, pendingResponses={}, deferring close",
								sessionId, pendingErr);
						lastWriteErrorAt = System.currentTimeMillis();
						connectionClosed = true;
						java.util.Map<String, Object> pendingmErr = new java.util.HashMap<String, Object>();
						pendingmErr.put("pending", pendingErr);
						java.util.Map<String, Object> statusmErr = new java.util.HashMap<String, Object>();
						statusmErr.put("status", "ERROR");
						statusmErr.put("reason", "error");
						statusmErr.put("cause",
								event.getThrowable() == null ? "<null>" : event.getThrowable().getMessage());
						// Put correlation info in 'corr' and status/cause in 'outcome'
						io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_CLOSED",
								sessionId, null, pendingmErr, statusmErr, null);
						closeSessionWithDrain(sessionId, asyncContext);
						disposeHeartbeat();
					} else {
						connectionClosed = true;
						// Closed with no pending: include cause in outcome when available
						java.util.Map<String, Object> statusm = new java.util.HashMap<String, Object>();
						statusm.put("status", "CLOSED");
						statusm.put("cause",
								event.getThrowable() == null ? "<null>" : event.getThrowable().getMessage());
						io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_CLOSED",
								sessionId, null, java.util.Collections.singletonMap("pending", pendingErr), statusm,
								null);
						closeSessionWithDrain(sessionId, asyncContext);
						disposeHeartbeat();
					}
				}

				@Override
				public void onStartAsync(AsyncEvent event) throws IOException {
					// Nothing to do
				}
			});

			// Schedule a lightweight SSE comment heartbeat every 2s to keep
			// intermediaries alive
			this.heartbeat = dedicatedScheduler.schedulePeriodically(() -> {
				try {
					writer.write(": heartbeat\n\n");
					writer.flush();
					if (writer.checkError()) {
						long now = System.currentTimeMillis();
						if (lastWriteErrorAt == null) {
							lastWriteErrorAt = now;
							logger.warn(
									"SERVER SSE heartbeat detected writer error (first time): sessionId={}, thread={}, will wait {}ms before closing",
									sessionId, Thread.currentThread().getName(), WRITE_ERROR_GRACE_MS);
						} else if (now - lastWriteErrorAt > WRITE_ERROR_GRACE_MS) {
							logger.warn(
									"SERVER SSE heartbeat detected persistent writer error: sessionId={}, thread={} - closing after grace",
									sessionId, Thread.currentThread().getName());
							connectionClosed = true;
							closeSessionWithDrain(sessionId, asyncContext);
							disposeHeartbeat();
						}
					}
				} catch (Exception e) {
					logger.debug("SERVER SSE heartbeat failed for session {}: {}", sessionId, e.getMessage());
				}
			}, 2L, 2L, TimeUnit.SECONDS);
			logger.debug("Session transport {} initialized with SSE writer", sessionId);
		}

		private void disposeHeartbeat() {
			try {
				if (this.heartbeat != null && !this.heartbeat.isDisposed()) {
					this.heartbeat.dispose();
				}
			} catch (Exception e) {
				logger.debug("Failed to dispose heartbeat for session {}: {}", this.sessionId, e.getMessage());
			}
		}

		/**
		 * Sends a JSON-RPC message to the client through the SSE connection.
		 * 
		 * @param message The JSON-RPC message to send
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				// Check connection state before attempting to send
				if (connectionClosed) {
					String kind = message instanceof McpSchema.JSONRPCResponse ? "RESPONSE"
							: (message instanceof McpSchema.JSONRPCRequest ? "REQUEST" : "NOTIFICATION");
					logger.warn("SERVER: Attempt to send {} on closed connection: sessionId={}", kind, sessionId);
					throw new McpTransportException("SSE connection already closed for session: " + sessionId);
				}

				try {
					// Extract message info for diagnostic logging
					String kind;
					Object id;
					boolean isResponse = false;

					if (message instanceof McpSchema.JSONRPCRequest) {
						kind = "REQUEST";
						id = ((McpSchema.JSONRPCRequest) message).id();
					} else if (message instanceof McpSchema.JSONRPCResponse) {
						kind = "RESPONSE";
						id = ((McpSchema.JSONRPCResponse) message).id();
						isResponse = true;
					} else if (message instanceof McpSchema.JSONRPCNotification) {
						kind = "NOTIFICATION";
						id = null;
					} else {
						kind = "UNKNOWN";
						id = null;
					}

					logger.info("SERVER prepareResponse: sessionId={}, kind={}, id={}, thread={}", sessionId, kind, id,
							Thread.currentThread().getName());
					// Structured log: server preparing SSE response
					java.util.Map<String, Object> prepmap = new java.util.HashMap<String, Object>();
					prepmap.put("id", id);
					prepmap.put("kind", kind);
					java.util.Map<String, Object> corrPrep = new java.util.HashMap<String, Object>();
					corrPrep.put("initiatorId", id);
					corrPrep.put("parentId", null);
					corrPrep.put("seq", 0);
					io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "SSE", "S_PREP_SSE_RESP",
							sessionId, prepmap, corrPrep, java.util.Collections.singletonMap("status", "PENDING"),
							null);

					String jsonText = jsonMapper.writeValueAsString(message);
					sendEvent(writer, MESSAGE_EVENT_TYPE, jsonText);

					// Structured event emitted below (S_SSE_SEND). Remove duplicate
					// textual log.
					java.util.Map<String, Object> sendm = new java.util.HashMap<String, Object>();
					sendm.put("id", id);
					sendm.put("kind", kind);
					java.util.Map<String, Object> corrSend = new java.util.HashMap<String, Object>();
					corrSend.put("initiatorId", id);
					corrSend.put("parentId", null);
					corrSend.put("seq", 0);
					io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_SEND",
							sessionId, sendm, corrSend, java.util.Collections.singletonMap("status", "SUCCESS"), null);
				} catch (Exception e) {
					// Extract message type for error handling
					boolean isResponse = message instanceof McpSchema.JSONRPCResponse;
					String kind = isResponse ? "RESPONSE"
							: (message instanceof McpSchema.JSONRPCRequest ? "REQUEST" : "NOTIFICATION");

					logger.error("Failed to send {} to session {}: {}", kind, sessionId, e.getMessage());

					// Only close the SSE connection for RESPONSE send failures
					// For REQUEST (internal, like sampling/createMessage) or NOTIFICATION
					// failures,
					// keep the connection open - the client can still respond via HTTP
					// POST
					if (isResponse) {
						logger.warn("SERVER SSE CONNECTION CLOSING due to RESPONSE send error: sessionId={}, thread={}",
								sessionId, Thread.currentThread().getName());
						connectionClosed = true;
						// Emit a structured S_SSE_CLOSED with cause for send failures
						java.util.Map<String, Object> statusSendErr = new java.util.HashMap<String, Object>();
						statusSendErr.put("status", "ERROR");
						statusSendErr.put("reason", "send-error");
						statusSendErr.put("cause", e.getMessage());
						io.modelcontextprotocol.logging.McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_CLOSED",
								sessionId, null, java.util.Collections.singletonMap("pending", Integer.valueOf(1)),
								statusSendErr, null);
						closeSessionWithDrain(sessionId, asyncContext);
						disposeHeartbeat();
					} else {
						logger.warn("SERVER SSE send error for {} (keeping connection open): sessionId={}, thread={}",
								kind, sessionId, Thread.currentThread().getName());
						// Connection stays open - client can still send responses via
						// HTTP POST
					}
				}
			});
		}

		/**
		 * Converts data from one type to another using the configured JsonMapper.
		 * 
		 * @param data    The source data object to convert
		 * @param typeRef The target type reference
		 * @param <T>     The target type
		 * @return The converted object of type T
		 */
		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return jsonMapper.convertValue(data, typeRef);
		}

		/**
		 * Initiates a graceful shutdown of the transport.
		 * 
		 * @return A Mono that completes when the shutdown is complete
		 */
		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				logger.debug("SERVER SSE CONNECTION CLOSING (graceful): sessionId={}, thread={}", sessionId,
						Thread.currentThread().getName());
				connectionClosed = true;
				closeSessionWithDrain(sessionId, asyncContext);
				logger.debug("SERVER SSE ASYNC CONTEXT COMPLETED: sessionId={}, thread={}", sessionId,
						Thread.currentThread().getName());
			});
		}

		/**
		 * Closes the transport immediately.
		 */
		@Override
		public void close() {
			logger.info("SERVER SSE CONNECTION CLOSING (immediate): sessionId={}, thread={}", sessionId,
					Thread.currentThread().getName());
			connectionClosed = true;
			closeSessionWithDrain(sessionId, asyncContext);
			disposeHeartbeat();
			logger.info("SERVER SSE ASYNC CONTEXT COMPLETED: sessionId={}, thread={}", sessionId,
					Thread.currentThread().getName());
		}

	}

	/**
	 * Helper class to hold message type information for logging.
	 */
	private static class MessageInfo {

		final String kind;

		final Object id;

		MessageInfo(String kind, Object id) {
			this.kind = kind;
			this.id = id;
		}

	}

	/**
	 * Creates a new Builder instance for configuring and creating instances of
	 * HttpServletSseServerTransportProvider.
	 * 
	 * @return A new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of HttpServletSseServerTransportProvider.
	 * <p>
	 * This builder provides a fluent API for configuring and creating instances of
	 * HttpServletSseServerTransportProvider with custom settings.
	 */
	public static class Builder {

		private McpJsonMapper jsonMapper;

		private String baseUrl = DEFAULT_BASE_URL;

		private String messageEndpoint;

		private String sseEndpoint = DEFAULT_SSE_ENDPOINT;

		private McpTransportContextExtractor<HttpServletRequest> contextExtractor = (
				serverRequest) -> McpTransportContext.EMPTY;

		private Duration keepAliveInterval;

		/**
		 * Sets the JsonMapper implementation to use for serialization/deserialization.
		 * If not specified, a JacksonJsonMapper will be created from the configured
		 * ObjectMapper.
		 * 
		 * @param jsonMapper The JsonMapper to use
		 * @return This builder instance for method chaining
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the base URL for the server transport.
		 * 
		 * @param baseUrl The base URL to use
		 * @return This builder instance for method chaining
		 */
		public Builder baseUrl(String baseUrl) {
			Assert.notNull(baseUrl, "Base URL must not be null");
			this.baseUrl = baseUrl;
			return this;
		}

		/**
		 * Sets the endpoint path where clients will send their messages.
		 * 
		 * @param messageEndpoint The message endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder messageEndpoint(String messageEndpoint) {
			Assert.hasText(messageEndpoint, "Message endpoint must not be empty");
			this.messageEndpoint = messageEndpoint;
			return this;
		}

		/**
		 * Sets the endpoint path where clients will establish SSE connections.
		 * <p>
		 * If not specified, the default value of {@link #DEFAULT_SSE_ENDPOINT} will be
		 * used.
		 * 
		 * @param sseEndpoint The SSE endpoint path
		 * @return This builder instance for method chaining
		 */
		public Builder sseEndpoint(String sseEndpoint) {
			Assert.hasText(sseEndpoint, "SSE endpoint must not be empty");
			this.sseEndpoint = sseEndpoint;
			return this;
		}

		/**
		 * Sets the context extractor for extracting transport context from the request.
		 * 
		 * @param contextExtractor The context extractor to use. Must not be null.
		 * @return this builder instance
		 * @throws IllegalArgumentException if contextExtractor is null
		 */
		public HttpServletSseServerTransportProvider.Builder contextExtractor(
				McpTransportContextExtractor<HttpServletRequest> contextExtractor) {
			Assert.notNull(contextExtractor, "Context extractor must not be null");
			this.contextExtractor = contextExtractor;
			return this;
		}

		/**
		 * Sets the interval for keep-alive pings.
		 * <p>
		 * If not specified, keep-alive pings will be disabled.
		 * 
		 * @param keepAliveInterval The interval duration for keep-alive pings
		 * @return This builder instance for method chaining
		 */
		public Builder keepAliveInterval(Duration keepAliveInterval) {
			this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Builds a new instance of HttpServletSseServerTransportProvider with the
		 * configured settings.
		 * 
		 * @return A new HttpServletSseServerTransportProvider instance
		 * @throws IllegalStateException if jsonMapper or messageEndpoint is not set
		 */
		public HttpServletSseServerTransportProvider build() {
			if (messageEndpoint == null) {
				throw new IllegalStateException("MessageEndpoint must be set");
			}
			return new HttpServletSseServerTransportProvider(
					jsonMapper == null ? McpJsonMapper.getDefault() : jsonMapper, baseUrl, messageEndpoint, sseEndpoint,
					keepAliveInterval, contextExtractor);
		}

	}

	protected ScheduledFuture<?> startHeartbeat(String sessionId, PrintWriter w, HttpServletResponse resp,
			SseSessionState state) {
		return null;
	}

}
