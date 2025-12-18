
package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.logging.McpLogging;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import org.slf4j.Logger;

/**
 * Listener "passivo" sul lifecycle dell'AsyncContext SSE. Non chiude né completa; lascia
 * la chiusura al transport interno.
 */
public final class SseAsyncListener implements AsyncListener {

	private final Logger logger;

	private final String sessionId;

	private final SseSessionState state;

	private final AsyncCompleteLogger asyncLogger; // callback iniettato per
													// S_ASYNC_COMPLETE (non usato qui)

	public SseAsyncListener(Logger logger, String sessionId, SseSessionState state, AsyncCompleteLogger asyncLogger) {
		this.logger = logger;
		this.sessionId = sessionId;
		this.state = state;
		this.asyncLogger = asyncLogger;
	}

	@Override
	public void onComplete(AsyncEvent event) throws IOException {
		// No-op: il provider/transport interno emette S_ASYNC_COMPLETE quando chiude
		// davvero.
		// Non duplicare log. (Diagnostica già presente nel transport interno)
	}

	@Override
	public void onTimeout(AsyncEvent event) throws IOException {
		// Se è già in chiusura/chiusa, ignora
		if (state.isClosed() || state.isClosing()) {
			return;
		}
		// Segna stato "timeout in corso" ma NON chiudere/completare qui.
		if (state.beginTimeoutClose()) {
			Map<String, Object> diagCorr = new HashMap<String, Object>();
			diagCorr.put("pending", Integer.valueOf(0));
			Map<String, Object> diagOutcome = new HashMap<String, Object>();
			diagOutcome.put("status", "ERROR");
			diagOutcome.put("reason", "onTimeout-signal");
			diagOutcome.put("cause", "Timeout");
			// Evento DIAGNOSTICO (non di chiusura): utile a tracciare il segnale del
			// container.
			McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_SIGNAL", sessionId, null, diagCorr, diagOutcome, null);
			// NIENTE async.complete() e NIENTE S_SSE_CLOSED: la chiusura vera la fa il
			// transport interno.
			state.completeTimeoutClose(); // idempotente; mantiene traccia
		}
	}

	@Override
	public void onError(AsyncEvent event) throws IOException {
		// Se è già in chiusura/chiusa, ignora
		if (state.isClosed() || state.isClosing()) {
			return;
		}
		if (state.beginErrorClose()) {
			Throwable t = event.getThrowable();
			Map<String, Object> diagCorr = new HashMap<String, Object>();
			diagCorr.put("pending", Integer.valueOf(0));
			Map<String, Object> diagOutcome = new HashMap<String, Object>();
			diagOutcome.put("status", "ERROR");
			diagOutcome.put("reason", "onError-signal");
			diagOutcome.put("cause", (t != null ? t.getClass().getSimpleName() : "Unknown"));
			// Evento DIAGNOSTICO (non di chiusura): traccia l'errore container/connector.
			McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_SIGNAL", sessionId, null, diagCorr, diagOutcome, null);
			// NIENTE async.complete() e NIENTE S_SSE_CLOSED: la chiusura vera la fa il
			// transport interno.
			state.completeErrorClose(); // idempotente; mantiene traccia
		}
	}

	@Override
	public void onStartAsync(AsyncEvent event) throws IOException {
		// No-op
	}

}