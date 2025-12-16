
package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.logging.McpLogging;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;

import org.slf4j.Logger;

public final class SseAsyncListener implements AsyncListener {

	private final Logger logger;

	private final String sessionId;

	private final SseSessionState state;

	private final AsyncCompleteLogger asyncLogger; // ✅ callback iniettato

	public SseAsyncListener(Logger logger, String sessionId, SseSessionState state, AsyncCompleteLogger asyncLogger) {
		this.logger = logger;
		this.sessionId = sessionId;
		this.state = state;
		this.asyncLogger = asyncLogger;
	}

	@Override
	public void onComplete(AsyncEvent event) throws IOException {
		// No-op: il provider emette già S_ASYNC_COMPLETE
		// (caller=closeSessionWithDrain).
		// Qui non duplicare log.
	}

	@Override
	public void onTimeout(AsyncEvent event) throws IOException {
		// Se è già in chiusura o chi // Se è già in chiusura o chiusa
		// (graceful/errore),
		// ignora il timeout
		if (state.isClosed() || state.isClosing()) {
			return;
		}
		// Avvia chiusura per timeout SOLO se era OPEN
		if (state.beginTimeoutClose()) {
			// corr: pending=0
			Map<String, Object> corr = new HashMap<String, Object>();
			corr.put("pending", Integer.valueOf(0));

			// outcome: CLOSED + cause=Timeout
			Map<String, Object> outcome = new HashMap<String, Object>();
			outcome.put("status", "CLOSED");
			outcome.put("cause", "Timeout");

			// Log S_SSE_CLOSED (cause Timeout)
			McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_CLOSED", sessionId, null, // jsonrpc
					corr, // corr
					outcome, // outcome
					null // extra
			);

			// ✅ completa + log unico S_ASYNC_COMPLETE
			AsyncContext async = event.getAsyncContext();
			asyncLogger.completeAndLogOnce(sessionId, async, "HttpServletSseServerTransportProvider.onTimeout");

			state.completeTimeoutClose();

		}
	}

	@Override
	public void onError(AsyncEvent event) throws IOException {
		// Se è già in chiusura o chiusa (graceful/timeout), ignora
		if (state.isClosed() || state.isClosing()) {
			return;
		}
		if (state.beginErrorClose()) {
			Throwable t = event.getThrowable();

			Map<String, Object> corr = new HashMap<String, Object>();
			corr.put("pending", Integer.valueOf(0));

			Map<String, Object> outcome = new HashMap<String, Object>();
			outcome.put("status", "CLOSED");
			outcome.put("cause", (t != null ? t.getClass().getSimpleName() : "Unknown"));

			McpLogging.logEvent(logger, "SERVER", "SSE", "S_SSE_CLOSED", sessionId, null, corr, outcome, null);

			// ✅ completa + log unico S_ASYNC_COMPLETE
			AsyncContext async = event.getAsyncContext();
			asyncLogger.completeAndLogOnce(sessionId, async, "HttpServletSseServerTransportProvider.onError");

			state.completeErrorClose();

		}
	}

	@Override
	public void onStartAsync(AsyncEvent event) throws IOException {
		// No-op
	}

}