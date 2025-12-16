
package io.modelcontextprotocol.server.transport;

import javax.servlet.AsyncContext;

/**
 * * Callback per completare l'AsyncContext e loggare S_ASYNC_COMPLETE una sola volta per
 * sessione.
 */
public interface AsyncCompleteLogger {

	void completeAndLogOnce(String sessionId, AsyncContext async, String caller);

}
