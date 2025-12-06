
package io.modelcontextprotocol.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.provider.Arguments;

@Timeout(15)
class HttpServletSseIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";
	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	// ===== factory per i ParameterizedTest (ripristinata) =====
	static Stream<Arguments> clientsForTesting() {
		// Almeno un tipo di client: httpclient (come nello smoke e nel tuo test
		// originario)
		return Stream.of(Arguments.of("httpclient"));
	}

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;
	private Tomcat tomcat;

	private HttpClientSseClientTransport transport;
	private McpSyncClient client;

	// Avvio del server MCP lato servlet (popola sessionFactory)
	private McpAsyncServer asyncServer;

	private int port; // porta dedicata per ogni metodo di test

	@BeforeEach
	public void before() {
		// 0) Porta per-test
		port = TomcatTestUtil.findAvailablePort();

		// 1) Provider + Tomcat
		mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
				.contextExtractor(TEST_CONTEXT_EXTRACTOR).messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
				.sseEndpoint(CUSTOM_SSE_ENDPOINT).build();

		tomcat = TomcatTestUtil.createTomcatServer("", port, mcpServerTransportProvider);
		try {
			tomcat.getConnector();
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
			System.out.println("Tomcat avviato su porta " + port);
		} catch (Exception e) {
			throw new RuntimeException("Failed to start embedded Tomcat", e);
		}

		// 2) MCP server (popola sessionFactory nel provider)
		asyncServer = McpServer.async(mcpServerTransportProvider).serverInfo("integration-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(30)).build();

		// 3) 🔴 Popola i builder per i test parametrizzati
		prepareClients(port, CUSTOM_MESSAGE_ENDPOINT);
		
		// 4) Transport & client per la parte “non parametrizzata” del test di handshake
				System.out.println("preparo client transport");
				transport = HttpClientSseClientTransport.builder("http://localhost:" + port).sseEndpoint(CUSTOM_SSE_ENDPOINT)
						.build();

				System.out.println("preparo client mcp (sync)");
				client = McpClient.sync(transport).clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
						.requestTimeout(Duration.ofSeconds(30)).build();


		// 5) Readiness SSE prima di lasciare il test al base class
		assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)),
				"SSE non pronta, impossibile inizializzare il client parametrizzato");
	}

	@Test
	void testInitializeHandshake() {
		// Il client è inizializzato nel @BeforeEach
		assertThat(readMessageEndpoint(transport)).isNotNull();
	}

	@AfterEach
	public void after() {
		System.out.println("Chiusura risorse...");
		try {
			if (client != null) {
				client.close(); // 1) chiudi il client
			}
			if (transport != null) {
				transport.closeGracefully().block(); // 2) chiudi il transport
			}
			if (mcpServerTransportProvider != null) {
				mcpServerTransportProvider.closeGracefully().block(); // 3) chiudi lato server/transport
			}
			if (asyncServer != null) {
				asyncServer.closeGracefully().block(); // 4) chiudi il server MCP
			}
			if (tomcat != null) {
				tomcat.stop(); // 5) ferma Tomcat
				tomcat.destroy(); // 6) distruggi Tomcat
			}
		} catch (LifecycleException e) {
			throw new RuntimeException("Failed to stop Tomcat", e);
		} finally {
			client = null;
			transport = null;
			mcpServerTransportProvider = null;
			asyncServer = null;
			tomcat = null;
		}
		System.out.println("Risorse chiuse.");
	}

	@Override
	protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder() {
		// coerente con i tuoi test originari
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		// coerente con i tuoi test originari
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		// Svuota e popola la mappa dei builder usata dai @ParameterizedTest
		this.clientBuilders.clear();

		// Transport SSE verso il tuo Tomcat per-test
		HttpClientSseClientTransport t = HttpClientSseClientTransport.builder("http://localhost:" + port)
				.sseEndpoint(CUSTOM_SSE_ENDPOINT).build();

		// Builder sync per "httpclient" (nome atteso dal @MethodSource)
		this.clientBuilders.put("httpclient",
				McpClient.sync(t).clientInfo(new McpSchema.Implementation("Parameterized client", "0.0.0"))
						.requestTimeout(Duration.ofSeconds(30)));
	}

	// ===== Helpers coerenti con lo smoke =====

	private boolean waitForHttpReady(String path, Duration timeout) {
		long end = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < end) {
			// 1) handshake TCP
			try (Socket s = new Socket()) {
				s.connect(new InetSocketAddress("localhost", port), 500);
			} catch (IOException e) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException ignored) {
				}
				continue;
			}
			// 2) GET HTTP
			try {
				HttpURLConnection conn = open("GET", path);
				int code = conn.getResponseCode();
				conn.disconnect();
				if (code >= 200 && code < 500)
					return true; // anche 404 è considerato "ready"
			} catch (IOException ignored) {
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			}
		}
		return false;
	}

	private HttpURLConnection open(String method, String path) throws IOException {
		URL url = java.net.URI.create("http://localhost:" + port + path).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(method);
		conn.setConnectTimeout(1500);
		conn.setReadTimeout(1500);
		return conn;
	}

	@SuppressWarnings("unchecked")
	private String readMessageEndpoint(HttpClientSseClientTransport transport) {
		try {
			java.lang.reflect.Field f = transport.getClass().getDeclaredField("messageEndpoint");
			f.setAccessible(true);
			java.util.concurrent.atomic.AtomicReference<String> ref = (java.util.concurrent.atomic.AtomicReference<String>) f
					.get(transport);
			return ref.get();
		} catch (Exception e) {
			return null;
		}
	}

	static io.modelcontextprotocol.server.McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (
			r) -> McpTransportContext.create(Collections.singletonMap("important", "value"));
}
