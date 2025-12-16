
package io.modelcontextprotocol.server.transport;

import java.util.concurrent.atomic.AtomicReference;

public final class SseSessionState {

	public enum Phase {

		OPEN, // stream attiva
		CLOSING_GRACEFUL, // chiusura volontaria in corso
		CLOSED_GRACEFUL, // chiusura volontaria completata
		TIMEOUT_CLOSING, // timeout rilevato, chiusura in corso
		CLOSED_TIMEOUT, // chiusura per timeout completata
		ERROR_CLOSING, // errore, chiusura in corso
		CLOSED_ERROR // chiusura per errore completata

	}

	private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.OPEN);

	public Phase get() {
		return phase.get();
	}

	/** Avvia chiusura graceful se la sessione è OPEN. */
	public boolean beginGracefulClose() {
		return phase.compareAndSet(Phase.OPEN, Phase.CLOSING_GRACEFUL);
	}

	/** Completa chiusura graceful (idempotente). */
	public boolean completeGracefulClose() {
		Phase p = phase.get();
		if (p == Phase.CLOSING_GRACEFUL) {
			return phase.compareAndSet(Phase.CLOSING_GRACEFUL, Phase.CLOSED_GRACEFUL);
		}
		return false;
	}

	/** Avvia chiusura per timeout solo se la sessione è OPEN. */
	public boolean beginTimeoutClose() {
		return phase.compareAndSet(Phase.OPEN, Phase.TIMEOUT_CLOSING);
	}

	/** Completa chiusura per timeout (idempotente). */
	public boolean completeTimeoutClose() {
		Phase p = phase.get();
		if (p == Phase.TIMEOUT_CLOSING) {
			return phase.compareAndSet(Phase.TIMEOUT_CLOSING, Phase.CLOSED_TIMEOUT);
		}
		return false;
	}

	/** Avvia chiusura per errore se la sessione è OPEN. */
	public boolean beginErrorClose() {
		return phase.compareAndSet(Phase.OPEN, Phase.ERROR_CLOSING);
	}

	/** Completa chiusura per errore (idempotente). */
	public boolean completeErrorClose() {
		Phase p = phase.get();
		if (p == Phase.ERROR_CLOSING) {
			return phase.compareAndSet(Phase.ERROR_CLOSING, Phase.CLOSED_ERROR);
		}
		return false;
	}

	public boolean isClosed() {
		Phase p = phase.get();
		return p == Phase.CLOSED_GRACEFUL || p == Phase.CLOSED_TIMEOUT || p == Phase.CLOSED_ERROR;
	}

	public boolean isClosing() {
		Phase p = phase.get();
		return p == Phase.CLOSING_GRACEFUL || p == Phase.TIMEOUT_CLOSING || p == Phase.ERROR_CLOSING;
	}

}