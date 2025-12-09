/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpInitRequestHandler;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Sinks;

/**
 * Represents a Model Context Protocol (MCP) session on the server side. It manages
 * bidirectional JSON-RPC communication with the client.
 */
public class McpServerSession implements McpLoggableSession {

	private static final Logger logger = LoggerFactory.getLogger(McpServerSession.class);

	private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>>();

	private final String id;

	/** Duration to wait for request responses before timing out */
	private final Duration requestTimeout;

	private final AtomicLong requestCounter = new AtomicLong(0);

	private final McpInitRequestHandler initRequestHandler;

	private final Map<String, McpRequestHandler<?>> requestHandlers;

	private final Map<String, McpNotificationHandler> notificationHandlers;

	private final McpServerTransport transport;

	private final Sinks.One<McpAsyncServerExchange> exchangeSink = Sinks.one();

	private final AtomicReference<McpSchema.ClientCapabilities> clientCapabilities = new AtomicReference<McpSchema.ClientCapabilities>();

	private final AtomicReference<McpSchema.Implementation> clientInfo = new AtomicReference<McpSchema.Implementation>();

	private static final int STATE_UNINITIALIZED = 0;

	private static final int STATE_INITIALIZING = 1;

	private static final int STATE_INITIALIZED = 2;

	private final AtomicInteger state = new AtomicInteger(STATE_UNINITIALIZED);

	private volatile McpSchema.LoggingLevel minLoggingLevel = McpSchema.LoggingLevel.INFO;

	/**
	 * Creates a new server session with the given parameters and the transport to use.
	 */
	public McpServerSession(String id, Duration requestTimeout, McpServerTransport transport,
			McpInitRequestHandler initHandler, Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		this.id = id;
		this.requestTimeout = requestTimeout;
		this.transport = transport;
		this.initRequestHandler = initHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	/** Deprecated constructor */
	@Deprecated
	public McpServerSession(String id, Duration requestTimeout, McpServerTransport transport,
			McpInitRequestHandler initHandler, InitNotificationHandler initNotificationHandler,
			Map<String, McpRequestHandler<?>> requestHandlers,
			Map<String, McpNotificationHandler> notificationHandlers) {
		this.id = id;
		this.requestTimeout = requestTimeout;
		this.transport = transport;
		this.initRequestHandler = initHandler;
		this.requestHandlers = requestHandlers;
		this.notificationHandlers = notificationHandlers;
	}

	/** Retrieve the session id. */
	public String getId() {
		return this.id;
	}

	/** Initialization callback with client capabilities and info. */
	public void init(McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo) {
		this.clientCapabilities.lazySet(clientCapabilities);
		this.clientInfo.lazySet(clientInfo);
	}

	private String generateRequestId() {
		return this.id + "-" + this.requestCounter.getAndIncrement();
	}

	@Override
	public void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel) {
		Assert.notNull(minLoggingLevel, "minLoggingLevel must not be null");
		this.minLoggingLevel = minLoggingLevel;
	}

	@Override
	public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel) {
		return loggingLevel.level() >= this.minLoggingLevel.level();
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
		final String requestId = this.generateRequestId();
		logger.info("SERVER sendRequest: method={}, id={}, thread={} - SENDING REQUEST TO CLIENT", method, requestId, Thread.currentThread().getName());
		return Mono.<McpSchema.JSONRPCResponse>create(sink -> {
			this.pendingResponses.put(requestId, sink);
			logger.info("SERVER sendRequest: id={}, thread={} - Added to pendingResponses, count={}", requestId, Thread.currentThread().getName(), this.pendingResponses.size());
			McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method,
					requestId, requestParams);
			this.transport.sendMessage(jsonrpcRequest).subscribe(v -> {
				logger.info("SERVER sendRequest: id={} - Message sent via transport successfully", requestId);
			}, error -> {
				logger.error("SERVER sendRequest: id={} - Failed to send via transport: {}", requestId, error.getMessage());
				this.pendingResponses.remove(requestId);
				sink.error(error);
			});
		}).timeout(requestTimeout).handle((jsonRpcResponse, s) -> {
			if (jsonRpcResponse.error() != null) {
			 logger.error("SERVER sendRequest: id={} COMPLETED with error={}",
                         requestId, jsonRpcResponse.error());
				s.error(new McpError(jsonRpcResponse.error()));
			}
			else {
			 logger.info("SERVER sendRequest: id={} COMPLETED with success", requestId);
				if (typeRef.getType().equals(Void.class)) {
					s.complete();
				}
				else {
					s.next(this.transport.unmarshalFrom(jsonRpcResponse.result(), typeRef));
				}
			}
		});
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				method, params);
		return this.transport.sendMessage(jsonrpcNotification);
	}

	/** Dispatch incoming message to appropriate handler. */
	public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
		return Mono.deferContextual(ctx -> {
			McpTransportContext transportContext = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			if (message instanceof McpSchema.JSONRPCResponse) {
				McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) message;
				logger.debug("Received response: {}", response);
				logger.info("SERVER handle(): incoming RESPONSE id={}, thread={}, pendingCount={}", response.id(), Thread.currentThread().getName(), pendingResponses.size());
				if (response.id() != null) {
					MonoSink<McpSchema.JSONRPCResponse> sink = pendingResponses.remove(response.id());
					if (sink == null) {
						logger.warn("Unexpected response for unknown id {}", response.id());
						logger.warn("SERVER session: response id={} NOT FOUND in pending (pendingCount={})", response.id(), pendingResponses.size());
					}
					else {
						logger.info("SERVER session: response id={} DELIVERED to pending sink, thread={}", response.id(), Thread.currentThread().getName());
						sink.success(response);
					}
				}
				else {
					logger.error("Discarded MCP request response without session id. "
							+ "This is an indication of a bug in the request sender code that can lead to memory "
							+ "leaks as pending requests will never be completed.");
				}
				return Mono.empty();
			}
			else if (message instanceof McpSchema.JSONRPCRequest) {
				McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) message;
				logger.debug("Received request: {}", request);
				logger.info("SERVER handle(): incoming REQUEST method={}, id={}, thread={}", request.method(), request.id(), Thread.currentThread().getName());
				logger.info("SERVER handle(): BEFORE handleIncomingRequest - thread={} is about to process request", Thread.currentThread().getName());
				return handleIncomingRequest(request, transportContext).onErrorResume(error -> {
					McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = (error instanceof McpError
							&& ((McpError) error).getJsonRpcError() != null) ? ((McpError) error).getJsonRpcError()
									: new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
											error.getMessage(), McpError.aggregateExceptionMessages(error));
					McpSchema.JSONRPCResponse errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
							request.id(), null, jsonRpcError);
					// TODO: Should the error go to SSE or back as POST return?
					logger.info("SERVER handle(): send ERROR response id={} via transport", request.id());
					return this.transport.sendMessage(errorResponse).then(Mono.empty());
				}).flatMap(this.transport::sendMessage);
			}
			else if (message instanceof McpSchema.JSONRPCNotification) {
				McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) message;
				logger.debug("Received notification: {}", notification);
				return handleIncomingNotification(notification, transportContext)
					.doOnError(error -> logger.error("Error handling notification: {}", error.getMessage()));
			}
			else {
				logger.warn("Received unknown message type: {}", message);
				return Mono.empty();
			}
		});
	}

	/** Route JSON-RPC request to handler. */
	private Mono<McpSchema.JSONRPCResponse> handleIncomingRequest(McpSchema.JSONRPCRequest request,
			McpTransportContext transportContext) {
		logger.info("SERVER handleIncomingRequest: method={}, id={}, thread={}", request.method(), request.id(), Thread.currentThread().getName());
		return Mono.defer(() -> {
			Mono<?> resultMono;
			if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
				McpSchema.InitializeRequest initializeRequest = transport.unmarshalFrom(request.params(),
						new TypeRef<McpSchema.InitializeRequest>() {
						});
				this.state.lazySet(STATE_INITIALIZING);
				this.init(initializeRequest.capabilities(), initializeRequest.clientInfo());
				resultMono = this.initRequestHandler.handle(initializeRequest);
			}
			else {
				McpRequestHandler<?> handler = this.requestHandlers.get(request.method());
				if (handler == null) {
					MethodNotFoundError error = getMethodNotFoundError(request.method());
					logger.warn("SERVER handleIncomingRequest: handler NOT FOUND for method={}", request.method());
					return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
							new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
									error.message(), error.data())));
				}
				logger.info("SERVER handleIncomingRequest: FOUND handler for method={}, id={}, thread={} - About to call handler", request.method(), request.id(), Thread.currentThread().getName());
				resultMono = this.exchangeSink.asMono()
					.flatMap(exchange -> {
						logger.info("SERVER handleIncomingRequest: EXECUTING handler for method={}, id={}, thread={}", request.method(), request.id(), Thread.currentThread().getName());
						return handler.handle(copyExchange(exchange, transportContext), request.params());
					});
			}
			return resultMono
				.map(result -> {
	                McpSchema.JSONRPCResponse resp = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null);
	                logger.info("SERVER prepareResponse: id={}, thread={} (will send via SSE)", request.id(), Thread.currentThread().getName());
	                return resp;
				})
				.onErrorResume(error -> {
					McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = (error instanceof McpError
							&& ((McpError) error).getJsonRpcError() != null) ? ((McpError) error).getJsonRpcError()
									: new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
											error.getMessage(), McpError.aggregateExceptionMessages(error));
					logger.info("SERVER prepareErrorResponse: id={} (will send via SSE)", request.id());
					return Mono.just(
							new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, jsonRpcError));
				});
		});
	}

	/** Route JSON-RPC notification to handler. */
	private Mono<Void> handleIncomingNotification(McpSchema.JSONRPCNotification notification,
			McpTransportContext transportContext) {
		return Mono.defer(() -> {
			if (McpSchema.METHOD_NOTIFICATION_INITIALIZED.equals(notification.method())) {
				this.state.lazySet(STATE_INITIALIZED);
				exchangeSink.tryEmitValue(new McpAsyncServerExchange(this.id, this, clientCapabilities.get(),
						clientInfo.get(), transportContext));
			}
			McpNotificationHandler handler = notificationHandlers.get(notification.method());
			if (handler == null) {
				logger.warn("No handler registered for notification method: {}", notification);
				return Mono.empty();
			}
			return this.exchangeSink.asMono()
				.flatMap(exchange -> handler.handle(copyExchange(exchange, transportContext), notification.params()));
		});
	}

	/** Create per-request exchange copying context from cached exchange. */
	private McpAsyncServerExchange copyExchange(McpAsyncServerExchange exchange, McpTransportContext transportContext) {
		return new McpAsyncServerExchange(exchange.sessionId(), this, exchange.getClientCapabilities(),
				exchange.getClientInfo(), transportContext);
	}

	/** Java 8 replacement for record MethodNotFoundError. */
	public static final class MethodNotFoundError {

		private final String method;

		private final String message;

		private final Object data;

		public MethodNotFoundError(String method, String message, Object data) {
			this.method = method;
			this.message = message;
			this.data = data;
		}

		public String method() {
			return method;
		}

		public String message() {
			return message;
		}

		public Object data() {
			return data;
		}

	}

	private MethodNotFoundError getMethodNotFoundError(String method) {
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	@Override
	public Mono<Void> closeGracefully() {
		// TODO: clear pendingResponses and emit errors?
		return this.transport.closeGracefully();
	}

	@Override
	public void close() {
		// TODO: clear pendingResponses and emit errors?
		this.transport.close();
	}

	/**
	 * Request handler for the initialization request.
	 *
	 * @deprecated Use {@link McpInitRequestHandler}
	 */
	@Deprecated
	public interface InitRequestHandler {

		Mono<McpSchema.InitializeResult> handle(McpSchema.InitializeRequest initializeRequest);

	}

	/** Notification handler for the initialization notification from the client. */
	public interface InitNotificationHandler {

		Mono<Void> handle();

	}

	/**
	 * A handler for client-initiated notifications.
	 *
	 * @deprecated Use {@link McpNotificationHandler}
	 */
	@Deprecated
	public interface NotificationHandler {

		Mono<Void> handle(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * A handler for client-initiated requests.
	 *
	 * @param <T> response type
	 * @deprecated Use {@link McpRequestHandler}
	 */
	@Deprecated
	public interface RequestHandler<T> {

		Mono<T> handle(McpAsyncServerExchange exchange, Object params);

	}

	/**
	 * Factory for creating server sessions which delegate to a provided 1:1 transport.
	 */
	@FunctionalInterface
	public interface Factory {

		McpServerSession create(McpServerTransport sessionTransport);

	}

}
