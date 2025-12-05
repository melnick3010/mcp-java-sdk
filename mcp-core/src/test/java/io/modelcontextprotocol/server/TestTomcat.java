
package io.modelcontextprotocol.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;

import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

public class TestTomcat {

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	private Tomcat tomcat;

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Collections.singletonMap("important", "value"));

	public static void main(String[] args) {
		new TestTomcat().run();
	}

	private void run() {
		McpSyncClient client = null;
		McpAsyncServer server = null;

		try {
			// --- Server: transport provider con endpoints custom ---
			mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
				.contextExtractor(TEST_CONTEXT_EXTRACTOR)
				.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
				.sseEndpoint(CUSTOM_SSE_ENDPOINT)
				.build();

			// Avvia Tomcat embedded e verifica stato
			tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);

			// Costruisci MCP server (async)
			server = McpServer.async(mcpServerTransportProvider)
				.serverInfo("test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(1000))
				.build();

			// --- Client: crea transport SSE e CONNETTI ---
			HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder("http://localhost:" + PORT)
				.sseEndpoint(CUSTOM_SSE_ENDPOINT)
				.build();

			// connect() ritorna subito; la lettura SSE prosegue in background.
			transport.connect(msgMono -> Mono.empty()).block();

			// ATTENDI che l'endpoint dei messaggi venga scoperto via SSE (evento
			// 'endpoint')
			boolean ready = waitForMessageEndpoint(transport, 5000 /* ms */);
			if (!ready) {
				throw new IllegalStateException("Message endpoint not discovered within timeout");
			}

			// Ora il client può inizializzare senza race
			client = McpClient.sync(transport)
				.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.requestTimeout(Duration.ofSeconds(1000))
				.build();

			McpSchema.InitializeResult init = client.initialize();
			assertThat(init).isNotNull();

		}
		catch (Exception e) {
			// Se qualcosa va storto, stampa per diagnosi
			e.printStackTrace();
		}
		finally {
			// --- Chiusura risorse ---
			try {
				if (client != null) {
					client.close();
				}
			}
			catch (Exception ignore) {
			}

			try {
				if (server != null) {
					server.closeGracefully().block();
				}
			}
			catch (Exception ignore) {
			}

			try {
				if (tomcat != null) {
					tomcat.stop();
				}
			}
			catch (Exception ignore) {
			}
		}
	}

	/**
	 * Attende finché il transport ha scoperto l'endpoint dei messaggi (via evento SSE
	 * "endpoint"). Usa reflection per leggere il campo private AtomicReference<String>
	 * messageEndpoint.
	 */
	private boolean waitForMessageEndpoint(HttpClientSseClientTransport transport, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (isMessageEndpointAvailable(transport)) {
				return true;
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return isMessageEndpointAvailable(transport);
	}

	@SuppressWarnings("unchecked")
	private boolean isMessageEndpointAvailable(HttpClientSseClientTransport transport) {
		try {
			Field f = transport.getClass().getDeclaredField("messageEndpoint");
			f.setAccessible(true);
			AtomicReference<String> ref = (AtomicReference<String>) f.get(transport);
			return ref != null && ref.get() != null;
		}
		catch (Exception e) {
			return false;
		}
	}

}
