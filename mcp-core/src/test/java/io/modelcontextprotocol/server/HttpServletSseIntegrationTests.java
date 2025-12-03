
package io.modelcontextprotocol.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

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

    static Stream<Arguments> clientsForTesting() {
        return Stream.of(Arguments.of("httpclient"));
    }

    @BeforeEach
    public void before() {
        // Configura il provider con endpoints custom
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
        } catch (Exception e) {
            throw new RuntimeException("Failed to start embedded Tomcat", e);
        }

        // Costruisci il transport SSE e connetti
        transport = HttpClientSseClientTransport.builder("http://localhost:" + PORT)
                .sseEndpoint(CUSTOM_SSE_ENDPOINT)
                .build();

        transport.connect(msgMono -> Mono.empty()).block();

        // Attendi che il messageEndpoint venga scoperto via SSE
        await().atMost(5, TimeUnit.SECONDS)
               .pollInterval(100, TimeUnit.MILLISECONDS)
               .until(this::isMessageEndpointAvailable);

        // Ora prepara il client MCP
        clientBuilders.put("httpclient",
                McpClient.sync(transport)
                        .clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
                        .requestTimeout(Duration.ofSeconds(30)));
    }

    @Test
    void testInitializeHandshake() {
        client = clientBuilders.get("httpclient").build();
        McpSchema.InitializeResult result = client.initialize();
        assertThat(result).isNotNull();
        System.out.println("Initialize completato: " + result.protocolVersion());
    }

    @AfterEach
    public void after() {
        System.out.println("Chiusura risorse...");
        try {
            if (client != null) client.close();
            if (mcpServerTransportProvider != null) mcpServerTransportProvider.closeGracefully().block();
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
            }
        } catch (LifecycleException e) {
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

    private boolean isMessageEndpointAvailable() {
        try {
            Field f = transport.getClass().getDeclaredField("messageEndpoint");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            AtomicReference<String> ref = (AtomicReference<String>) f.get(transport);
            return ref.get() != null;
        } catch (Exception e) {
            return false;
        }
    }

    static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR =
            (r) -> McpTransportContext.create(Collections.singletonMap("important", "value"));
}
