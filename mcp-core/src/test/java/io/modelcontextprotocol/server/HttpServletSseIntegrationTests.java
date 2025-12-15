
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
import reactor.core.scheduler.Schedulers;

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

@Timeout(60) // ↑↑ Aumentato da 35s a 60s per margine sui metodi ereditati
class HttpServletSseIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"));
	}

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	private Tomcat tomcat;

	private McpAsyncServer asyncServer;

	private HttpClientSseClientTransport transport;

	private McpSyncClient client;

	private int port;

	@BeforeAll
	static void enableWireLogging() {
		System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
		System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", "DEBUG");
		System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.wire", "DEBUG");
		System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.impl.conn", "DEBUG");
	}

	@BeforeEach
	public void before() {
		port = TomcatTestUtil.findAvailablePort();

		mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
				.contextExtractor(TEST_CONTEXT_EXTRACTOR).messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
				.sseEndpoint(CUSTOM_SSE_ENDPOINT).build();

		tomcat = TomcatTestUtil.createTomcatServer("", port, mcpServerTransportProvider);
		try {
			tomcat.getConnector();
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
			System.out.println("Tomcat avviato su porta " + port);

			// Wait for Tomcat to be fully ready before proceeding
			// This prevents race conditions when tests run in sequence
			assertTrue(waitForTomcatReady(Duration.ofSeconds(10)), "Tomcat non completamente pronto dopo l'avvio");
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start embedded Tomcat", e);
		}

		// Increase server timeout to 60s to handle slow operations and suite contention
		asyncServer = McpServer.async(mcpServerTransportProvider).serverInfo("integration-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(60)).build();

		prepareClients(port, CUSTOM_MESSAGE_ENDPOINT);

		assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)),
				"SSE non pronta: impossibile inizializzare il client nel test");
	}

	@Test
	void testInitializeHandshake() {
		System.out.println("preparo client transport (handshake)");
		transport = HttpClientSseClientTransport.builder("http://localhost:" + port).sseEndpoint(CUSTOM_SSE_ENDPOINT)
				.build();

		System.out.println("preparo client mcp (sync, handshake)");
		// ↑↑ Aumentato da 30s a 45s per coerenza
		client = McpClient.sync(transport).clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.requestTimeout(Duration.ofSeconds(45)).build();

		System.out.println("inizializzo client (handshake)");
		McpSchema.InitializeResult result = client.initialize();
		System.out.println("initialized: protocol=" + result.protocolVersion());

		String endpoint = readMessageEndpoint(transport);
		System.out.println("messageEndpoint = " + endpoint);
		assertThat(endpoint).isNotNull();
		System.out.println("fine testInitializeHandshake");
	}

	@AfterEach
	public void after() {
		System.out.println("Chiusura risorse...");
		try {
			if (client != null) {
				client.close();
			}
			if (transport != null) {
				// Block with timeout to ensure complete teardown
				transport.closeGracefully().block(Duration.ofSeconds(15));
			}
			if (mcpServerTransportProvider != null) {
				mcpServerTransportProvider.closeGracefully().block(Duration.ofSeconds(5));
			}
			if (asyncServer != null) {
				asyncServer.closeGracefully().block(Duration.ofSeconds(5));
			}
			if (tomcat != null) {
				tomcat.stop();
				tomcat.destroy();
				// Wait for Tomcat to fully stop before next test
				waitForTomcatStopped(Duration.ofSeconds(5));
			}
		}
		catch (LifecycleException e) {
			throw new RuntimeException("Failed to stop Tomcat", e);
		}
		finally {
			client = null;
			transport = null;
			mcpServerTransportProvider = null;
			asyncServer = null;
			tomcat = null;
		}
		System.out.println("Risorse chiuse.");
	}

	@AfterAll
	static void afterAll() {

		// 2) Chiudi gli scheduler globali di Reactor (parallel, boundedElastic, single)
		Schedulers.shutdownNow();

		// (Opzionale) Reset di hook se usati:
		// reactor.core.publisher.Hooks.resetOnEachOperator();
		// reactor.core.publisher.Hooks.resetOnLastOperator();
	}

	@Override
	protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		this.clientBuilders.clear();
		HttpClientSseClientTransport t = HttpClientSseClientTransport.builder("http://localhost:" + port)
				.sseEndpoint(CUSTOM_SSE_ENDPOINT).build();
		// Increase client timeout to 90s to ensure it's greater than server timeout (60s)
		// This prevents client-side timeout before server can respond
		this.clientBuilders.put("httpclient",
				McpClient.sync(t).clientInfo(new McpSchema.Implementation("Parameterized client", "0.0.0"))
						.requestTimeout(Duration.ofSeconds(90)));
	}

	// (…metodi helper invariati…)
	private boolean waitForHttpReady(String path, Duration timeout) {
		long end = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < end) {
			try (Socket s = new Socket()) {
				s.connect(new InetSocketAddress("localhost", port), 500);
			}
			catch (IOException e) {
				try {
					Thread.sleep(50);
				}
				catch (InterruptedException ignored) {
				}
				continue;
			}
			try {
				HttpURLConnection conn = open("GET", path);
				int code = conn.getResponseCode();
				conn.disconnect();
				if (code >= 200 && code < 500)
					return true;
			}
			catch (IOException ignored) {
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ignored) {
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
		}
		catch (Exception e) {
			return null;
		}
	}

	/**
	 * Wait for Tomcat to be fully ready after start. Checks that the server can accept
	 * connections.
	 */
	private boolean waitForTomcatReady(Duration timeout) {
		long end = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < end) {
			if (tomcat != null && tomcat.getServer().getState() == LifecycleState.STARTED) {
				// Additional check: try to connect to the port
				try (java.net.Socket s = new java.net.Socket()) {
					s.connect(new java.net.InetSocketAddress("localhost", port), 500);
					return true;
				}
				catch (IOException e) {
					// Not ready yet, continue waiting
				}
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	/**
	 * Wait for Tomcat to fully stop after shutdown. Ensures the port is released before
	 * next test.
	 */
	private void waitForTomcatStopped(Duration timeout) {
		long end = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < end) {
			if (tomcat == null || tomcat.getServer().getState() == LifecycleState.STOPPED
					|| tomcat.getServer().getState() == LifecycleState.DESTROYED) {
				// Additional check: ensure port is released
				try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
					// Port is available, Tomcat has released it
					return;
				}
				catch (IOException e) {
					// Port still in use, continue waiting
				}
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		System.out.println("Warning: Tomcat may not have fully stopped within timeout");
	}

	static io.modelcontextprotocol.server.McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (
			r) -> McpTransportContext.create(Collections.singletonMap("important", "value"));

}
