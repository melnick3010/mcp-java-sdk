/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Default implementation of the MCP (Model Context Protocol) session that manages
 * bidirectional JSON-RPC communication between clients and servers.
 *
 * The session manages: - Request/response handling with unique message IDs - Notification
 * processing - Message timeout management - Transport layer abstraction
 */
public class McpClientSession implements McpSession {

	private static final Logger logger = LoggerFactory.getLogger(McpClientSession.class);

	/** Duration to wait for request responses before timing out */
	private final Duration requestTimeout;

	/** Transport layer implementation for message exchange */
	private final McpClientTransport transport;

	/** Map of pending responses keyed by request ID */
	private final ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>> pendingResponses = new ConcurrentHashMap<Object, MonoSink<McpSchema.JSONRPCResponse>>();

	/** Map of request handlers keyed by method name */
	private final ConcurrentHashMap<String, RequestHandler<?>> requestHandlers = new ConcurrentHashMap<String, RequestHandler<?>>();

	/** Map of notification handlers keyed by method name */
	private final ConcurrentHashMap<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<String, NotificationHandler>();

	/** Session-specific prefix for request IDs */
	private final String sessionPrefix = UUID.randomUUID().toString().substring(0, 8);

	/** Atomic counter for generating unique request IDs */
	private final AtomicLong requestCounter = new AtomicLong(0);

	/** Functional interface for handling incoming JSON-RPC requests. */
	@FunctionalInterface
	public interface RequestHandler<T> {

		/** Handles an incoming request with the given parameters. */
		Mono<T> handle(Object params);

	}

	/** Functional interface for handling incoming JSON-RPC notifications. */
	@FunctionalInterface
	public interface NotificationHandler {

		/** Handles an incoming notification with the given parameters. */
		Mono<Void> handle(Object params);

	}

	/**
	 * Creates a new McpClientSession with the specified configuration and handlers.
	 * @deprecated Use
	 * {@link #McpClientSession(Duration, McpClientTransport, Map, Map, Function)}
	 * instead.
	 */
	@Deprecated
	public McpClientSession(Duration requestTimeout, McpClientTransport transport,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers) {
		this(requestTimeout, transport, requestHandlers, notificationHandlers,
				new Function<Publisher<McpSchema.JSONRPCMessage>, Publisher<McpSchema.JSONRPCMessage>>() {
					@Override
					public Publisher<McpSchema.JSONRPCMessage> apply(Publisher<McpSchema.JSONRPCMessage> publisher) {
						return publisher;
					}
				});
	}

	/**
	 * Creates a new McpClientSession with the specified configuration and handlers.
	 * @param requestTimeout Duration to wait for responses
	 * @param transport Transport implementation for message exchange
	 * @param requestHandlers Map of method names to request handlers
	 * @param notificationHandlers Map of method names to notification handlers
	 * @param connectHook Hook that allows transforming the connection Publisher prior to
	 * subscribing
	 */

	public McpClientSession(Duration requestTimeout, McpClientTransport transport,
			Map<String, RequestHandler<?>> requestHandlers, Map<String, NotificationHandler> notificationHandlers,
			Function<Publisher<McpSchema.JSONRPCMessage>, Publisher<McpSchema.JSONRPCMessage>> connectHook) {
		Assert.notNull(requestTimeout, "The requestTimeout can not be null");
		Assert.notNull(transport, "The transport can not be null");
		Assert.notNull(requestHandlers, "The requestHandlers can not be null");
		Assert.notNull(notificationHandlers, "The notificationHandlers can not be null");
		Assert.notNull(connectHook, "The connectHook can not be null");

		this.requestTimeout = requestTimeout;
		this.transport = transport;
		this.requestHandlers.putAll(requestHandlers);
		this.notificationHandlers.putAll(notificationHandlers);

		// 👉 Il handler ORA mappa davvero REQUEST → RESPONSE.
		Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = mono -> Mono
				.from(connectHook.apply(mono.flatMap(msg -> {
					if (msg instanceof McpSchema.JSONRPCRequest) {
						// Genera la RESPONSE e RITORNALA (NO invio qui)
						return handleIncomingRequest((McpSchema.JSONRPCRequest) msg)
								.cast(McpSchema.JSONRPCMessage.class);
					}
					else {
						// RESPONSE/NOTIFICATION: gestisci localmente (pending/notify) e
						// non postare
						McpClientSession.this.handle(msg);
						return Mono.just(msg);
					}
				})));

		this.transport.connect(handler).subscribe();
	}

	private void dismissPendingResponses() {
		for (Map.Entry<Object, MonoSink<McpSchema.JSONRPCResponse>> e : this.pendingResponses.entrySet()) {
			Object id = e.getKey();
			MonoSink<McpSchema.JSONRPCResponse> sink = e.getValue();
			logger.warn("Abruptly terminating exchange for request {}", id);
			sink.error(new RuntimeException("MCP session with server terminated"));
		}
		this.pendingResponses.clear();
	}

	private void handle(McpSchema.JSONRPCMessage message) {
		if (message instanceof McpSchema.JSONRPCResponse) {
			McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) message;
			logger.debug("Received response: {}", response);
			if (response.id() != null) {
				MonoSink<McpSchema.JSONRPCResponse> sink = pendingResponses.remove(response.id());
				if (sink == null) {
					logger.warn("Unexpected response for unknown id {}", response.id());
				}
				else {
					sink.success(response);
				}
			}
			else {
				logger.error("Discarded MCP request response without session id. "
						+ "This is an indication of a bug in the request sender code that can lead to memory "
						+ "leaks as pending requests will never be completed.");
			}
		}
		else if (message instanceof McpSchema.JSONRPCRequest) {
			McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) message;
			// Nessun invio della response qui:
			handleIncomingRequest(request).onErrorResume(error -> {
				McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = (error instanceof McpError
						&& ((McpError) error).getJsonRpcError() != null) ? ((McpError) error).getJsonRpcError()
								: new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
										error.getMessage(), McpError.aggregateExceptionMessages(error));
				return Mono.just(
						new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null, jsonRpcError));
			})
					// 👉 Non inviare: la response verrà resa al transport via
					// handler.apply(...)
					.subscribe(); // solo per completare eventuali side-effect; opzionale
		}
		else if (message instanceof McpSchema.JSONRPCNotification) {
			McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) message;
			logger.debug("Received notification: {}", notification);
			handleIncomingNotification(notification).onErrorComplete(t -> {
				logger.error("Error handling notification: {}", t.getMessage());
				return true;
			}).subscribe();
		}
		else {
			logger.warn("Received unknown message type: {}", message);
		}
	}

	// Costruisce la RESPONSE e la restituisce al transport (che deciderà
	// dove/postarla)
	private Mono<McpSchema.JSONRPCResponse> handleIncomingRequest(McpSchema.JSONRPCRequest request) {
		return Mono.defer(() -> {
			RequestHandler<?> handler = McpClientSession.this.requestHandlers.get(request.method());
			if (handler == null) {
				MethodNotFoundError error = getMethodNotFoundError(request.method());
				return Mono.just(new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
						new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.METHOD_NOT_FOUND,
								error.message(), error.data())));
			}
			return handler.handle(request.params()).map(
					result -> new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null));
		});
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
		if (McpSchema.METHOD_ROOTS_LIST.equals(method)) {
			return new MethodNotFoundError(method, "Roots not supported",
					Collections.<String, Object>singletonMap("reason", "Client does not have roots capability"));
		}
		return new MethodNotFoundError(method, "Method not found: " + method, null);
	}

	/**
	 * Handles an incoming JSON-RPC notification by routing it to the appropriate handler.
	 */
	private Mono<Void> handleIncomingNotification(McpSchema.JSONRPCNotification notification) {
		return Mono.defer(() -> {
			NotificationHandler handler = notificationHandlers.get(notification.method());
			if (handler == null) {
				logger.warn("No handler registered for notification method: {}", notification);
				return Mono.empty();
			}
			return handler.handle(notification.params());
		});
	}

	/** Generates a unique request ID in a non-blocking way. */
	private String generateRequestId() {
		return this.sessionPrefix + "-" + this.requestCounter.getAndIncrement();
	}

	/** Sends a JSON-RPC request and returns the response. */
	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
		final String requestId = this.generateRequestId();
		return Mono.deferContextual(
				ctx -> Mono.create(new java.util.function.Consumer<MonoSink<McpSchema.JSONRPCResponse>>() {
					@Override
					public void accept(MonoSink<McpSchema.JSONRPCResponse> pendingResponseSink) {
						logger.debug("Sending message for method {}", method);
						logger.info("CLIENT sendRequest: method={}, id={}, name={}", method, requestId,Thread.currentThread().getName());
						pendingResponses.put(requestId, pendingResponseSink);
						McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(
								McpSchema.JSONRPC_VERSION, method, requestId, requestParams);
						transport.sendMessage(jsonrpcRequest).contextWrite(ctx)
								.subscribe(new java.util.function.Consumer<Object>() {
									@Override
									public void accept(Object v) {
									}
								}, new java.util.function.Consumer<Throwable>() {
									@Override
									public void accept(Throwable error) {
										pendingResponses.remove(requestId);
										pendingResponseSink.error(error);
									}
								});
					}
				})).timeout(this.requestTimeout).handle((jsonRpcResponse, deliveredResponseSink) -> {
					logger.info("CLIENT receivedResponse: id={} (completing)", requestId);
					if (jsonRpcResponse.error() != null) {
						logger.error("Error handling request: {}", jsonRpcResponse.error());
						deliveredResponseSink.error(new McpError(jsonRpcResponse.error()));
					}
					else {
						if (typeRef.getType().equals(Void.class)) {
							deliveredResponseSink.complete();
						}
						else {
							deliveredResponseSink.next(transport.unmarshalFrom(jsonRpcResponse.result(), typeRef));
						}
					}
				});
	}

	/** Sends a JSON-RPC notification. */
	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		McpSchema.JSONRPCNotification jsonrpcNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				method, params);
		return this.transport.sendMessage(jsonrpcNotification);
	}

	/** Closes the session gracefully, allowing pending operations to complete. */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(new Runnable() {
			@Override
			public void run() {
				dismissPendingResponses();
			}
		});
	}

	/**
	 * Closes the session immediately, potentially interrupting pending operations.
	 */
	@Override
	public void close() {
		dismissPendingResponses();
	}

}
