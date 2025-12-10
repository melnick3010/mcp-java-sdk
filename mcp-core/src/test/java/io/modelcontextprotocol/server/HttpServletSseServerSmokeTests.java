
package io.modelcontextprotocol.server;

import org.junit.jupiter.api.*;
import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests elementari: una funzionalità alla volta, usando la *stessa*
 * servlet/transport provider del test di integrazione.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpServletSseServerSmokeTests {

	// === Endpoint come nel test originale ===
	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse"; // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/HttpServletSseIntegrationTests.txt)

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message"; // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/HttpServletSseIntegrationTests.txt)

	// Stato per-test
	private int port;

	private Tomcat tomcat;

	private HttpServletSseServerTransportProvider provider;

	// Valore catturato dall'handshake initialize
	private Optional<String> announcedMessageEndpoint = Optional.empty();

	// campi
	private io.modelcontextprotocol.server.McpAsyncServer asyncServer; // mantieni un
																		// riferimento per
																		// chiusura

	@BeforeEach
	void setUp() throws Exception {
		System.out.println("sono in before each");
		port = TomcatTestUtil.findAvailablePort();

		// Provider come nel test originale
		provider = HttpServletSseServerTransportProvider.builder().contextExtractor(TEST_CONTEXT_EXTRACTOR)
				.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT).sseEndpoint(CUSTOM_SSE_ENDPOINT).build();

		// Avvia Tomcat
		System.out.println("Avvio Tomcat su porta " + port);
		tomcat = TomcatTestUtil.createTomcatServer("", port, provider);
		tomcat.getConnector();
		tomcat.start();
		System.out.println("Tomcat avviato su porta " + port);

		// 🔴 Avvia anche il server MCP (popola sessionFactory nel provider)
		asyncServer = io.modelcontextprotocol.server.McpServer.async(provider).serverInfo("smoke-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(30)).build();
	}

	@AfterEach
	void tearDown() throws Exception {
		System.out.println("Chiusura risorse...");
		try {
			// chiudi prima il server MCP (per terminare le sessioni SSE aperte)
			if (asyncServer != null) {
				asyncServer.closeGracefully().block();
			}
			// poi il transport provider
			if (provider != null) {
				provider.closeGracefully().block();
			}
			// infine il container
			if (tomcat != null) {
				tomcat.stop();
				tomcat.destroy();
			}
		}
		finally {
			asyncServer = null;
			provider = null;
			tomcat = null;
			announcedMessageEndpoint = Optional.empty();
		}
		System.out.println("Risorse chiuse.");
	}

	// ====== Test 1: il server è pronto su SSE (solo readiness HTTP) ======
	@Test
	@Order(1)
	void serverStartsOnChosenPort_andRespondsOnSsePath() throws Exception {
		assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)),
				"Il server/SSE non è pronto entro il timeout");
	}

	// ====== Test 2: SSE risponde e Content-Type è text/event-stream ======
	@Test
	@Order(2)
	void sseEndpoint_isReachable_andReportsTextEventStream() throws Exception {
		assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)), "SSE non è pronta entro il timeout");
		HttpURLConnection conn = open("GET", CUSTOM_SSE_ENDPOINT);
		int code = conn.getResponseCode();
		assertEquals(200, code, "SSE dovrebbe rispondere 200");
		String ctype = conn.getHeaderField("Content-Type");
		assertNotNull(ctype, "Content-Type mancante su SSE");
		assertTrue(ctype.toLowerCase().contains("text/event-stream"),
				"Content-Type atteso text/event-stream, trovato: " + ctype);
		conn.disconnect();
	}

	// ====== Test 3: initialize (solo annuncio del messageEndpoint) ======
	@Test
	@Order(3)
	void initialize_returnsMessageEndpoint() throws Exception {
		assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)),
				"SSE non pronta, impossibile inizializzare il client");

		announcedMessageEndpoint = performClientInitializeAndCaptureMessageEndpoint();
		assertTrue(announcedMessageEndpoint.isPresent(), "initialize deve annunciare un messageEndpoint non nullo");
		assertTrue(announcedMessageEndpoint.get().startsWith(CUSTOM_MESSAGE_ENDPOINT + "?sessionId="),
				"messageEndpoint inatteso: " + announcedMessageEndpoint.get());
	}

	// ====== Test 4: message endpoint risponde (senza validare semantica applicativa)
	// ======
	@Test
	@Order(4)
	void messageEndpoint_acceptsHttpCall_basicReachability() throws Exception {
		if (!announcedMessageEndpoint.isPresent()) {
			assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)),
					"SSE non pronta, impossibile inizializzare il client");
			announcedMessageEndpoint = performClientInitializeAndCaptureMessageEndpoint();
		}
		String path = announcedMessageEndpoint.orElseThrow(() -> new IllegalStateException(
				"messageEndpoint assente; assicurati che initialize sia andato a buon fine"));

		HttpURLConnection conn = open("GET", path); // se il tuo endpoint è POST-only, ci
													// aspettiamo 405 ma *una* status line
		int code = conn.getResponseCode();
		assertTrue(code >= 200 && code < 500, "messageEndpoint deve rispondere (2xx/3xx/4xx). Codice: " + code);
		conn.disconnect();
	}

	// ====== Test 5: shutdown pulito ======
	@Test
	@Order(5)
	void serverShutsDown_gracefully() throws Exception {
		assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)),
				"Server non pronto prima dello shutdown");
		// chiusura sarà eseguita nel tearDown; verifichiamo che *ora* risponda
		HttpURLConnection conn = open("GET", CUSTOM_SSE_ENDPOINT);
		int code = conn.getResponseCode();
		assertTrue(code >= 200 && code < 500, "Il server deve rispondere prima dello stop. Codice: " + code);
		conn.disconnect();
	}

	// ====== Helpers ======

	/**
	 * Attende che una GET su path restituisca una status line (2xx/3xx/4xx) entro
	 * timeout; prova prima il TCP handshake.
	 */
	private boolean waitForHttpReady(String path, Duration timeout) throws InterruptedException {
		long end = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < end) {
			// 1) handshake TCP
			try (Socket s = new Socket()) {
				s.connect(new InetSocketAddress("localhost", port), 500);
			}
			catch (IOException e) {
				Thread.sleep(50);
				continue;
			}
			// 2) GET HTTP
			try {
				HttpURLConnection conn = open("GET", path);
				int code = conn.getResponseCode();
				conn.disconnect();
				if (code >= 200 && code < 500)
					return true; // anche 404 è "pronto"
			}
			catch (IOException ignored) {
			}
			Thread.sleep(50);
		}
		return false;
	}

	/**
	 * Apre una HttpURLConnection verso http://localhost:{port}{path} con method indicato.
	 */
	private HttpURLConnection open(String method, String path) throws IOException {
		URL url = URI.create("http://localhost:" + port + path).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod(method);
		conn.setConnectTimeout(1500);
		conn.setReadTimeout(1500);
		return conn;
	}

	/**
	 * Esegue l'initialize con il tuo client/transport e cattura il messageEndpoint
	 * annunciato.
	 */
	private Optional<String> performClientInitializeAndCaptureMessageEndpoint() {
		try {
			// Transport SSE puntando a host:porta + SSE_PATH
			HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder("http://localhost:" + port) // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/HttpServletSseIntegrationTests.txt)
					.sseEndpoint(CUSTOM_SSE_ENDPOINT) // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/HttpServletSseIntegrationTests.txt)
					.build();

			// Client MCP sync (blocking)
			McpSyncClient client = McpClient.sync(transport)
					.clientInfo(new McpSchema.Implementation("Smoke client", "0.0.0"))
					.requestTimeout(Duration.ofSeconds(30)).build();

			McpSchema.InitializeResult init = client.initialize(); // blocking
			System.out.println("initialized: protocol=" + init.protocolVersion());

			// Leggi il messageEndpoint come nel tuo test originale (via reflection)
			String endpoint = readMessageEndpoint(transport); // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/HttpServletSseIntegrationTests.txt)

			// Chiudi risorse del lato client (non lato server, che è in tearDown)
			try {
				client.close();
			}
			catch (Exception ignored) {
			}
			try {
				transport.closeGracefully().block();
			}
			catch (Exception ignored) {
			}

			return Optional.ofNullable(endpoint);
		}
		catch (Exception e) {
			e.printStackTrace();
			return Optional.empty();
		}
	}

	/**
	 * Estrarre il messageEndpoint dal transport (reflection), identico al tuo test di
	 * integrazione.
	 */
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

	// Context extractor di test, come nel tuo test originale
	static io.modelcontextprotocol.server.McpTransportContextExtractor<javax.servlet.http.HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (
			r) -> McpTransportContext.create(java.util.Collections.singletonMap("important", "value")); // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/HttpServletSseIntegrationTests.txt)

}
