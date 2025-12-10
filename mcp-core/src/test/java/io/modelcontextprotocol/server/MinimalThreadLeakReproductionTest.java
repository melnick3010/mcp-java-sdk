package io.modelcontextprotocol.server;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.*;
import reactor.core.scheduler.Schedulers;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;

/**
 * Minimal test case to reproduce and analyze the thread leak warning: "The web
 * application [ROOT] appears to have started a thread named [parallel-3] but has failed
 * to stop it. This is very likely to create a memory leak."
 *
 * This test isolates the SSE initialization handshake to identify the root cause of
 * Reactor scheduler threads not being properly shut down before Tomcat stops.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MinimalThreadLeakReproductionTest {

	private static final String SSE_ENDPOINT = "/sse";

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private Tomcat tomcat;

	private HttpServletSseServerTransportProvider transportProvider;

	private McpAsyncServer asyncServer;

	private HttpClientSseClientTransport clientTransport;

	private McpSyncClient client;

	private int port;

	@BeforeEach
	void setup() throws Exception {
		System.out.println("\n=== SETUP START ===");

		// Find available port
		port = TomcatTestUtil.findAvailablePort();
		System.out.println("Using port: " + port);

		// Create server transport provider
		transportProvider = HttpServletSseServerTransportProvider.builder()
				.contextExtractor(req -> McpTransportContext.create(Collections.singletonMap("test", "value")))
				.messageEndpoint(MESSAGE_ENDPOINT).sseEndpoint(SSE_ENDPOINT).build();

		// Create and start Tomcat
		tomcat = TomcatTestUtil.createTomcatServer("", port, transportProvider);
		tomcat.getConnector();
		tomcat.start();

		if (tomcat.getServer().getState() != LifecycleState.STARTED) {
			throw new IllegalStateException("Tomcat failed to start");
		}
		System.out.println("Tomcat started successfully");

		// Create async server
		asyncServer = McpServer.async(transportProvider).serverInfo("minimal-test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(30)).build();

		// Wait for server to be ready
		if (!waitForHttpReady(SSE_ENDPOINT, Duration.ofSeconds(5))) {
			throw new IllegalStateException("Server not ready in time");
		}
		System.out.println("Server is ready");
		System.out.println("=== SETUP COMPLETE ===\n");
	}

	/**
	 * Test 1: Reproduce the thread leak with standard cleanup order
	 */
	@Test
	@Order(1)
	void testStandardCleanup_ReproducesThreadLeak() {
		System.out.println("\n=== TEST 1: Standard Cleanup (Reproduces Thread Leak) ===");

		// Perform SSE handshake
		performSseHandshake();

		System.out.println("Test completed - cleanup will follow in @AfterEach");
		System.out.println("EXPECTED: Thread leak warning should appear in logs");
	}

	/**
	 * Test 2: Try shutting down Reactor schedulers BEFORE closing resources
	 */
	@Test
	@Order(2)
	void testEarlySchedulerShutdown_MayPreventThreadLeak() {
		System.out.println("\n=== TEST 2: Early Scheduler Shutdown ===");

		// Perform SSE handshake
		performSseHandshake();

		// Close client and transport first
		closeClientResources();

		// Shutdown Reactor schedulers BEFORE closing server and Tomcat
		System.out.println("Shutting down Reactor schedulers early...");
		Schedulers.shutdownNow();

		// Give threads time to terminate
		try {
			Thread.sleep(500);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		System.out.println("Test completed - remaining cleanup will follow");
		System.out.println("EXPECTED: Thread leak warning should NOT appear (or be reduced)");
	}

	/**
	 * Test 3: Add explicit delays to allow async operations to complete
	 */
	@Test
	@Order(3)
	void testWithDelays_AllowAsyncCompletion() {
		System.out.println("\n=== TEST 3: With Delays for Async Completion ===");

		// Perform SSE handshake
		performSseHandshake();

		// Wait for any pending async operations
		System.out.println("Waiting for async operations to complete...");
		try {
			Thread.sleep(1000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		System.out.println("Test completed - cleanup will follow");
		System.out.println("EXPECTED: Analyze if delay helps reduce thread leak");
	}

	@AfterEach
	void cleanup() {
		System.out.println("\n=== CLEANUP START ===");

		try {
			// Close client resources if not already closed
			if (client != null || clientTransport != null) {
				closeClientResources();
			}

			// Close server resources
			if (transportProvider != null) {
				System.out.println("Closing transport provider...");
				transportProvider.closeGracefully().block(Duration.ofSeconds(5));
			}

			if (asyncServer != null) {
				System.out.println("Closing async server...");
				asyncServer.closeGracefully().block(Duration.ofSeconds(5));
			}

			// Stop Tomcat
			if (tomcat != null) {
				System.out.println("Stopping Tomcat...");
				tomcat.stop();
				tomcat.destroy();
			}

		}
		catch (LifecycleException e) {
			System.err.println("Error during Tomcat shutdown: " + e.getMessage());
			e.printStackTrace();
		}
		catch (Exception e) {
			System.err.println("Error during cleanup: " + e.getMessage());
			e.printStackTrace();
		}
		finally {
			client = null;
			clientTransport = null;
			transportProvider = null;
			asyncServer = null;
			tomcat = null;
		}

		System.out.println("=== CLEANUP COMPLETE ===\n");
	}

	@AfterAll
	static void finalCleanup() {
		System.out.println("\n=== FINAL CLEANUP ===");
		System.out.println("Shutting down Reactor schedulers (if not already done)...");
		Schedulers.shutdownNow();
		System.out.println("=== ALL TESTS COMPLETE ===\n");
	}

	// Helper methods

	private void performSseHandshake() {
		System.out.println("Creating client transport...");
		clientTransport = HttpClientSseClientTransport.builder("http://localhost:" + port).sseEndpoint(SSE_ENDPOINT)
				.build();

		System.out.println("Creating MCP sync client...");
		client = McpClient.sync(clientTransport)
				.clientInfo(new McpSchema.Implementation("Minimal Test Client", "1.0.0"))
				.requestTimeout(Duration.ofSeconds(30)).build();

		System.out.println("Initializing client (performing handshake)...");
		McpSchema.InitializeResult result = client.initialize();

		System.out.println("Handshake completed successfully");
		System.out.println("Protocol version: " + result.protocolVersion());
	}

	private void closeClientResources() {
		try {
			if (client != null) {
				System.out.println("Closing client...");
				client.close();
				client = null;
			}

			if (clientTransport != null) {
				System.out.println("Closing client transport...");
				clientTransport.closeGracefully().block(Duration.ofSeconds(5));
				clientTransport = null;
			}
		}
		catch (Exception e) {
			System.err.println("Error closing client resources: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private boolean waitForHttpReady(String path, Duration timeout) {
		long endTime = System.nanoTime() + timeout.toNanos();

		while (System.nanoTime() < endTime) {
			// Check if port is open
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress("localhost", port), 500);
			}
			catch (IOException e) {
				sleep(50);
				continue;
			}

			// Check if endpoint responds
			try {
				HttpURLConnection conn = openConnection("GET", path);
				int code = conn.getResponseCode();
				conn.disconnect();

				if (code >= 200 && code < 500) {
					return true;
				}
			}
			catch (IOException ignored) {
				// Continue trying
			}

			sleep(50);
		}

		return false;
	}

	private HttpURLConnection openConnection(String method, String path) throws IOException {
		URL url = java.net.URI.create("http://localhost:" + port + path).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(method);
		conn.setConnectTimeout(1500);
		conn.setReadTimeout(1500);
		return conn;
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}

// Made with Bob
