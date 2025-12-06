
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

import javax.servlet.http.HttpServletRequest;

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

@Timeout(15)
class HttpServletSseIntegrationTests extends AbstractMcpClientServerIntegrationTests {

    private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";            // come nel test originale [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)
    private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message"; // come nel test originale [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)

    private HttpServletSseServerTransportProvider mcpServerTransportProvider;
    private Tomcat tomcat;
    private HttpClientSseClientTransport transport;
    private McpSyncClient client;

    private int port; // porta dedicata per ogni metodo di test

    @BeforeEach
    public void before() {
        // 0) Porta per-test (elimina collisioni)
        port = TomcatTestUtil.findAvailablePort();                                  // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)

        // 1) Costruisci il provider come nel test originale
        mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
                .contextExtractor(TEST_CONTEXT_EXTRACTOR)
                .messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
                .sseEndpoint(CUSTOM_SSE_ENDPOINT)
                .build();                                                            // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)

        // 2) Avvia Tomcat embedded usando la tua utility
        tomcat = TomcatTestUtil.createTomcatServer("", port, mcpServerTransportProvider); // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)
        try {
            tomcat.getConnector(); // assicura la creazione del connector
            tomcat.start();
            assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
            System.out.println("Tomcat avviato su porta " + port);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start embedded Tomcat", e);
        }

        // 3) Transport SSE client-side
        System.out.println("preparo client transport");
        transport = HttpClientSseClientTransport.builder("http://localhost:" + port) // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)
                .sseEndpoint(CUSTOM_SSE_ENDPOINT)                                     // [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)
                .build();

        // 4) Client MCP sync
        System.out.println("preparo client mcp (sync)");
        client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        // 5) Readiness SSE prima di initialize (evita race)
        assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(6)),
                   "SSE non pronta, impossibile inizializzare il client");

        // 6) initialize (blocking) + diagnostica endpoint
        System.out.println("inizializzo client");
        McpSchema.InitializeResult init = client.initialize();
        System.out.println("initialized: protocol=" + init.protocolVersion());
        System.out.println("messageEndpoint = " + readMessageEndpoint(transport));
        System.out.println("fine before");
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
                client.close();                       // 1) chiudi il client
            }
            if (transport != null) {
                transport.closeGracefully().block();  // 2) chiudi il transport
            }
            if (mcpServerTransportProvider != null) {
                mcpServerTransportProvider.closeGracefully().block(); // 3) chiudi lato server
            }
            if (tomcat != null) {
                tomcat.stop();                        // 4) ferma Tomcat
                tomcat.destroy();                     // 5) distruggi Tomcat
            }
        } catch (LifecycleException e) {
            throw new RuntimeException("Failed to stop Tomcat", e);
        } finally {
            client = null;
            transport = null;
            mcpServerTransportProvider = null;
            tomcat = null;
        }
        System.out.println("Risorse chiuse.");
    }

    @Override
    protected io.modelcontextprotocol.server.McpServer.AsyncSpecification<?> prepareAsyncServerBuilder() {
        return io.modelcontextprotocol.server.McpServer.async(this.mcpServerTransportProvider); // come nel tuo test [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)
    }

    @Override
    protected io.modelcontextprotocol.server.McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
        return io.modelcontextprotocol.server.McpServer.sync(this.mcpServerTransportProvider);  // come nel tuo test [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)
    }

    @Override
    protected void prepareClients(int unusedPort, String unusedEndpoint) {
        // Non usato: tutta la preparazione avviene in @BeforeEach
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
                if (code >= 200 && code < 500) return true; // anche 404 è considerato "ready"
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
            java.lang.reflect.Field f = transport.getClass().getDeclaredField("messageEndpoint"); // come nel tuo test [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)
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
            McpTransportContext.create(Collections.singletonMap("important", "value")); // come nel tuo test [1](https://ibm-my.sharepoint.com/personal/nicola_vaglica_it_ibm_com/Documents/File%20di%20Microsoft%20Copilot%20Chat/smoketest_completo.txt)
}
