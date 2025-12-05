
package io.modelcontextprotocol.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;

import org.junit.jupiter.params.provider.Arguments;
import reactor.core.publisher.Mono;

@Timeout(15)
class HttpServletSseIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	private Tomcat tomcat;

	private HttpClientSseClientTransport transport;

	private McpSyncClient client;

	private reactor.core.Disposable sseSubscription; // campo della classe

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"));
	}

	@BeforeEach
	public void before() {
		// 1) Avvio server + Tomcat
		mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
			System.out.println("Tomcat avviato su porta " + PORT);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start embedded Tomcat", e);
		}

		// 2) Avvio MCP server
		System.out.println("avvio mcp server");
		McpAsyncServer asyncServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.requestTimeout(Duration.ofSeconds(30))
			.build();

		// 3) Transport (senza connect manuale)
		System.out.println("preparo client transport");
		transport = HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.build();

		// 4) Client sync (wrappa l’async interno)
		System.out.println("preparo client mcp (sync)");
		client = McpClient.sync(transport)
			.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.requestTimeout(Duration.ofSeconds(30))
			.build();

		// 5) **Inizializza subito**: l’initializer aprirà la SSE e negozierà tutto
		System.out.println("inizializzo client");
		McpSchema.InitializeResult init = client.initialize(); // blocking
		System.out.println("initialized: protocol=" + init.protocolVersion());

		// 6) (Opzionale) Leggi/mostra l’endpoint a fini diagnostici
		System.out.println("messageEndpoint = " + readMessageEndpoint(transport));
		System.out.println("fine before");
	}

	@Test
	void testInitializeHandshake() {
		client = clientBuilders.get("httpclient").build();
		// McpSchema.InitializeResult result = client.initialize();

		System.out.println("About to initialize, endpoint=" + readMessageEndpoint(transport));
		McpSchema.InitializeResult result = client.initialize();
		System.out.println("Initialize OK, protocol=" + result.protocolVersion());

		assertThat(result).isNotNull();
		System.out.println("Initialize completato: " + result.protocolVersion());
	}

	@AfterEach
	public void after() {
		System.out.println("Chiusura risorse...");
		try {
			// Chiudi la sessione client MCP
			if (client != null) {
				client.close();
			}

			// Chiudi gentilmente la SSE client-side
			if (transport != null) {
				transport.closeGracefully().block();
			}

			// Chiudi lato server
			if (mcpServerTransportProvider != null) {
				mcpServerTransportProvider.closeGracefully().block();
			}

			if (tomcat != null) {
				tomcat.stop();
				tomcat.destroy();
			}
		}
		catch (LifecycleException e) {
			throw new RuntimeException("Failed to stop Tomcat", e);
		}
		System.out.println("Risorse chiuse.");
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		// Non usato: la logica è nel @BeforeEach
	}

	/*
	 * private boolean isMessageEndpointAvailable() { try { Field f =
	 * transport.getClass().getDeclaredField("messageEndpoint"); f.setAccessible(true);
	 *
	 * @SuppressWarnings("unchecked") AtomicReference<String> ref =
	 * (AtomicReference<String>) f.get(transport); return ref.get() != null; } catch
	 * (Exception e) { return false; } }
	 */

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Collections.singletonMap("important", "value"));

	private static int httpGet(int port, String path) throws IOException {
		java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(
				"http://localhost:" + port + path)
			.openConnection();
		con.setRequestMethod("GET");
		con.setConnectTimeout(2000);
		con.setReadTimeout(2000);
		return con.getResponseCode();
	}

	@SuppressWarnings("unchecked")
	private String readMessageEndpoint(HttpClientSseClientTransport transport) {
		try {
			java.lang.reflect.Field f = transport.getClass().getDeclaredField("messageEndpoint");
			f.setAccessible(true);
			java.util.concurrent.atomic.AtomicReference<String> ref = (java.util.concurrent.atomic.AtomicReference<String>) f
				.get(transport);
			return ref.get();
		}
		catch (Exception e) {
			return null;
		}
	}

	private boolean isMessageEndpointAvailable() {
		return readMessageEndpoint(transport) != null;
	}

}
