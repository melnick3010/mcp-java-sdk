/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.server.transport.TomcatTestUtil.HealthServlet;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class HttpServletSseIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	private Tomcat tomcat;

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"));
	}




@BeforeEach
public void before() {
    // Costruisci provider/servlet components (come fai già)
    mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
        .contextExtractor(TEST_CONTEXT_EXTRACTOR)
        .messageEndpoint(CUSTOM_MESSAGE_ENDPOINT) // es: "/messages"
        .sseEndpoint(CUSTOM_SSE_ENDPOINT)         // es: "/sse"
        .build();

    tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);

    // Context “vuoto”, ma sufficiente per mappare le servlet
    Context ctx = tomcat.addContext("", null);

    // Registra health servlet
    Tomcat.addServlet(ctx, "healthServlet", new TomcatTestUtil.HealthServlet());
    ctx.addServletMappingDecoded("/health", "healthServlet");

    // Registra gli endpoint reali del test (SSE / messages)
    // Questi metodi dipendono dal tuo codice: usa le tue servlet o helper
    // Esempio:
    // Tomcat.addServlet(ctx, "sseServlet", new SseServlet(mcpServerTransportProvider));
    // ctx.addServletMappingDecoded(CUSTOM_SSE_ENDPOINT, "sseServlet");
    // Tomcat.addServlet(ctx, "msgServlet", new MessageServlet(mcpServerTransportProvider));
    // ctx.addServletMappingDecoded(CUSTOM_MESSAGE_ENDPOINT, "msgServlet");

    try {
        tomcat.start();

        // Log stato
        System.out.println("Tomcat server state after start(): " + tomcat.getServer().getState());
        System.out.println("Connector state after start(): " + tomcat.getConnector().getState());

        // Attendi che /health risponda 200 (readiness del context + servlet)
        waitForEndpointReady(PORT, "/health", 5000);
        int rc = httpGet(PORT, "/health");
        System.out.println("Readiness check /health: " + rc);

        // Ora avvia il client, che troverà gli endpoint già pronti
        clientBuilders.put("httpclient",
            McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
                .sseEndpoint(CUSTOM_SSE_ENDPOINT)
                .build())
            .requestTimeout(Duration.ofSeconds(30)));

    } catch (Exception e) {
        throw new RuntimeException("Failed to start embedded Tomcat or endpoint not ready", e);
    }
}

private static int httpGet(int port, String path) throws IOException {
    HttpURLConnection con = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    con.setRequestMethod("GET");
    con.setConnectTimeout(2000);
    con.setReadTimeout(2000);
    return con.getResponseCode();
}

private static void waitForEndpointReady(int port, String path, int timeoutMs) throws Exception {
    long end = System.currentTimeMillis() + timeoutMs;
    Exception last = null;
    while (System.currentTimeMillis() < end) {
        try {
            if (httpGet(port, path) == 200) return;
        } catch (Exception e) {
            last = e;
        }
        Thread.sleep(100);
    }
    throw new IllegalStateException("Endpoint " + path + " not ready within " + timeoutMs + " ms", last);
}



	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
	}

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Collections.singletonMap("important", "value"));

}
