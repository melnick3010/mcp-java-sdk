
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

    // ===== factory per i @ParameterizedTest =====
    static Stream<Arguments> clientsForTesting() {
        return Stream.of(Arguments.of("httpclient"));  // chiave usata nei test parametrizzati
    }

    private HttpServletSseServerTransportProvider mcpServerTransportProvider;
    private Tomcat tomcat;
    private McpAsyncServer asyncServer;

    // Risorse opzionali (create solo in testInitializeHandshake)
    private HttpClientSseClientTransport transport;
    private McpSyncClient client;

    private int port; // porta dedicata per ogni metodo di test

    @BeforeEach
    public void before() {
        // 0) Porta per-test
        port = TomcatTestUtil.findAvailablePort();

        // 1) Costruisci provider + avvio Tomcat
        mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
                .contextExtractor(TEST_CONTEXT_EXTRACTOR)
                .messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
                .sseEndpoint(CUSTOM_SSE_ENDPOINT)
                .build();

        tomcat = TomcatTestUtil.createTomcatServer("", port, mcpServerTransportProvider);
        try {
            tomcat.getConnector(); // connector pronto
            tomcat.start();
            assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
            System.out.println("Tomcat avviato su porta " + port);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start embedded Tomcat", e);
        }

        // 2) MCP server (popola sessionFactory nel provider)
        asyncServer = McpServer.async(mcpServerTransportProvider)
                .serverInfo("integration-server", "1.0.0")
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        // 3) Popola i builder per i test parametrizzati
        prepareClients(port, CUSTOM_MESSAGE_ENDPOINT);

        // 4) Readiness SSE prima di lasciare i metodi di test lavorare
        assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)),
                   "SSE non pronta: impossibile inizializzare il client nel test");
    }

    /**
     * Test non-parametrizzato: costruisce e inizializza un client locale,
     * e verifica l'annuncio del messageEndpoint.
     */
    @Test
    void testInitializeHandshake() {
        // Costruisci transport + client locali
        System.out.println("preparo client transport (handshake)");
        transport = HttpClientSseClientTransport.builder("http://localhost:" + port)
                .sseEndpoint(CUSTOM_SSE_ENDPOINT)
                .build();

        System.out.println("preparo client mcp (sync, handshake)");
        client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        // initialize (blocking) — ora il messageEndpoint sarà valorizzato nel transport
        System.out.println("inizializzo client (handshake)");
        McpSchema.InitializeResult result = client.initialize();
        System.out.println("initialized: protocol=" + result.protocolVersion());

        // assert diagnostico sul messageEndpoint (non null)
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
                transport.closeGracefully().block();
            }
            if (mcpServerTransportProvider != null) {
                mcpServerTransportProvider.closeGracefully().block();
            }
            if (asyncServer != null) {
                asyncServer.closeGracefully().block();
            }
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
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
        return McpServer.async(this.mcpServerTransportProvider);
    }

    @Override
    protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
        return McpServer.sync(this.mcpServerTransportProvider);
    }

    @Override
    protected void prepareClients(int port, String mcpEndpoint) {
        // Popola i builder per i test parametrizzati
        this.clientBuilders.clear();

        HttpClientSseClientTransport t = HttpClientSseClientTransport.builder("http://localhost:" + port)
                .sseEndpoint(CUSTOM_SSE_ENDPOINT)
                .build();

        this.clientBuilders.put(
            "httpclient",
            McpClient.sync(t)
                     .clientInfo(new McpSchema.Implementation("Parameterized client", "0.0.0"))
                     .requestTimeout(Duration.ofSeconds(30))
        );
    }

    // ===== Helpers coerenti con lo smoke =====

    private boolean waitForHttpReady(String path, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            // 1) handshake TCP
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("localhost", port), 500);
            } catch (IOException e) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                continue;
            }
            // 2) GET HTTP
            try {
                HttpURLConnection conn = open("GET", path);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code >= 200 && code < 500) return true; // 2xx/3xx/4xx -> pronto
            } catch (IOException ignored) {}
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
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
            java.util.concurrent.atomic.AtomicReference<String> ref =
                    (java.util.concurrent.atomic.AtomicReference<String>) f.get(transport);
            return ref.get();
        } catch (Exception e) {
            return null;
        }
    }

    static io.modelcontextprotocol.server.McpTransportContextExtractor<HttpServletRequest>
        TEST_CONTEXT_EXTRACTOR = (r) ->
            McpTransportContext.create(Collections.singletonMap("important", "value"));
}
