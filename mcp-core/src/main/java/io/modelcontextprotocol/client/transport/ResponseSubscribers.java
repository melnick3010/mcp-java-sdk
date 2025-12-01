
/* Copyright 2024 - 2024 the original author or authors. */
package io.modelcontextprotocol.client.transport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.reactivestreams.Subscription;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpTransportException;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.FluxSink;

/**
 * Utility class providing various Subscribers for handling different types of HTTP
 * response bodies (SSE, aggregate responses, bodiless responses) in MCP clients.
 *
 * Java 8 compatible: no java.net.http.* dependencies.
 */
final class ResponseSubscribers {

	private static final Logger logger = LoggerFactory.getLogger(ResponseSubscribers.class);

	/* ----------------------------- Types “minimali” ----------------------------- */

	/**
	 * Minimal replacement for JDK HttpClient ResponseInfo.
	 */
	static final class SimpleResponseInfo {

		private final int statusCode;

		private final Map<String, List<String>> headers;

		SimpleResponseInfo(int statusCode, Map<String, List<String>> headers) {
			this.statusCode = statusCode;
			this.headers = headers;
		}

		public int statusCode() {
			return statusCode;
		}

		public Map<String, List<String>> headers() {
			return headers;
		}

	}

	/* Sostituisce: record SseEvent(String id, String event, String data) - Java 8 */
	static final class SseEvent {

		private final String id;

		private final String event;

		private final String data;

		SseEvent(String id, String event, String data) {
			this.id = id;
			this.event = event;
			this.data = data;
		}

		public String id() {
			return id;
		}

		public String event() {
			return event;
		}

		public String data() {
			return data;
		}

	}

	/* Sostituisce: sealed interface ResponseEvent ... - Java 8 */
	interface ResponseEvent {

		SimpleResponseInfo responseInfo();

	}

	/*
	 * Sostituisce: record DummyEvent(ResponseInfo responseInfo) implements ResponseEvent
	 */
	static final class DummyEvent implements ResponseEvent {

		private final SimpleResponseInfo responseInfo;

		DummyEvent(SimpleResponseInfo responseInfo) {
			this.responseInfo = responseInfo;
		}

		@Override
		public SimpleResponseInfo responseInfo() {
			return responseInfo;
		}

	}

	/*
	 * Sostituisce: record SseResponseEvent(ResponseInfo responseInfo, SseEvent sseEvent)
	 * ...
	 */
	static final class SseResponseEvent implements ResponseEvent {

		private final SimpleResponseInfo responseInfo;

		private final SseEvent sseEvent;

		SseResponseEvent(SimpleResponseInfo responseInfo, SseEvent sseEvent) {
			this.responseInfo = responseInfo;
			this.sseEvent = sseEvent;
		}

		@Override
		public SimpleResponseInfo responseInfo() {
			return responseInfo;
		}

		public SseEvent sseEvent() {
			return sseEvent;
		}

	}

	/*
	 * Sostituisce: record AggregateResponseEvent(ResponseInfo responseInfo, String data)
	 * ...
	 */
	static final class AggregateResponseEvent implements ResponseEvent {

		private final SimpleResponseInfo responseInfo;

		private final String data;

		AggregateResponseEvent(SimpleResponseInfo responseInfo, String data) {
			this.responseInfo = responseInfo;
			this.data = data;
		}

		@Override
		public SimpleResponseInfo responseInfo() {
			return responseInfo;
		}

		public String data() {
			return data;
		}

	}

	/* ------------------------- Factory: Subscribers ------------------------- */

	/**
	 * Java 8: restituisce il Subscriber che processa righe SSE. Alimentalo con righe
	 * (String) del body HTTP.
	 */
	static Subscriber<String> sseSubscriber(SimpleResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return new SseLineSubscriber(responseInfo, sink);
	}

	/**
	 * Java 8: restituisce il Subscriber che aggrega tutto il body in un’unica String.
	 */
	static Subscriber<String> aggregateSubscriber(SimpleResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return new AggregateSubscriber(responseInfo, sink);
	}

	/**
	 * Java 8: restituisce il Subscriber che emette un “dummy event” senza body (utile per
	 * ispezionare status/headers).
	 */
	static Subscriber<String> bodilessSubscriber(SimpleResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return new BodilessResponseLineSubscriber(responseInfo, sink);
	}

	/* -------------------------- Implementazioni --------------------------- */

	static final class SseLineSubscriber extends BaseSubscriber<String> {

		/** Pattern to extract data content from SSE "data:" lines. */
		private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

		/** Pattern to extract event ID from SSE "id:" lines. */
		private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

		/** Pattern to extract event type from SSE "event:" lines. */
		private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

		/** The sink for emitting parsed response events. */
		private final FluxSink<ResponseEvent> sink;

		/** StringBuilder for accumulating multi-line event data. */
		private final StringBuilder eventBuilder;

		/** Current event's ID, if specified. */
		private final AtomicReference<String> currentEventId;

		/** Current event's type, if specified. */
		private final AtomicReference<String> currentEventType;

		/** The response information from the HTTP response. */
		private final SimpleResponseInfo responseInfo;

		public SseLineSubscriber(SimpleResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.currentEventId = new AtomicReference<String>();
			this.currentEventType = new AtomicReference<String>();
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {
			sink.onRequest(n -> subscription.request(n));
			sink.onDispose(subscription::cancel);
		}

		@Override
		protected void hookOnNext(String line) {
			if (line.isEmpty()) {
				// Empty line means end of event
				if (this.eventBuilder.length() > 0) {
					String eventData = this.eventBuilder.toString();
					SseEvent sseEvent = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
					this.sink.next(new SseResponseEvent(responseInfo, sseEvent));
					this.eventBuilder.setLength(0);
				}
			}
			else {
				if (line.startsWith("data:")) {
					Matcher matcher = EVENT_DATA_PATTERN.matcher(line);
					if (matcher.find()) {
						this.eventBuilder.append(matcher.group(1).trim()).append("\n");
					}
					upstream().request(1);
				}
				else if (line.startsWith("id:")) {
					Matcher matcher = EVENT_ID_PATTERN.matcher(line);
					if (matcher.find()) {
						this.currentEventId.set(matcher.group(1).trim());
					}
					upstream().request(1);
				}
				else if (line.startsWith("event:")) {
					Matcher matcher = EVENT_TYPE_PATTERN.matcher(line);
					if (matcher.find()) {
						this.currentEventType.set(matcher.group(1).trim());
					}
					upstream().request(1);
				}
				else if (line.startsWith(":")) {
					// Ignore comment lines starting with ":"
					logger.debug("Ignoring comment line: {}", line);
					upstream().request(1);
				}
				else {
					// If the response is not successful, emit an error
					this.sink.error(new McpTransportException(
							"Invalid SSE response. Status code: " + this.responseInfo.statusCode() + " Line: " + line));
				}
			}
		}

		@Override
		protected void hookOnComplete() {
			if (this.eventBuilder.length() > 0) {
				String eventData = this.eventBuilder.toString();
				SseEvent sseEvent = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
				this.sink.next(new SseResponseEvent(responseInfo, sseEvent));
			}
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

	static final class AggregateSubscriber extends BaseSubscriber<String> {

		/** The sink for emitting parsed response events. */
		private final FluxSink<ResponseEvent> sink;

		/** StringBuilder for accumulating multi-line event data. */
		private final StringBuilder eventBuilder;

		/** The response information from the HTTP response. */
		private final SimpleResponseInfo responseInfo;

		volatile boolean hasRequestedDemand = false;

		public AggregateSubscriber(SimpleResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {
			sink.onRequest(n -> {
				if (!hasRequestedDemand) {
					subscription.request(Long.MAX_VALUE);
				}
				hasRequestedDemand = true;
			});
			sink.onDispose(subscription::cancel);
		}

		@Override
		protected void hookOnNext(String line) {
			this.eventBuilder.append(line).append("\n");
		}

		@Override
		protected void hookOnComplete() {
			if (hasRequestedDemand) {
				String data = this.eventBuilder.toString();
				this.sink.next(new AggregateResponseEvent(responseInfo, data));
			}
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

	static final class BodilessResponseLineSubscriber extends BaseSubscriber<String> {

		/** The sink for emitting parsed response events. */
		private final FluxSink<ResponseEvent> sink;

		private final SimpleResponseInfo responseInfo;

		volatile boolean hasRequestedDemand = false;

		public BodilessResponseLineSubscriber(SimpleResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {
			sink.onRequest(n -> {
				if (!hasRequestedDemand) {
					subscription.request(Long.MAX_VALUE);
				}
				hasRequestedDemand = true;
			});
			sink.onDispose(subscription::cancel);
		}

		@Override
		protected void hookOnComplete() {
			if (hasRequestedDemand) {
				// Emit a dummy event so callers can inspect status/headers.
				this.sink.next(new DummyEvent(responseInfo));
			}
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

}
