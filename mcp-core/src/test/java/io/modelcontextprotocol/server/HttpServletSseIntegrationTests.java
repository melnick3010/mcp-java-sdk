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
    mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
        .contextExtractor(TEST_CONTEXT_EXTRACTOR)
        .messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
        .sseEndpoint(CUSTOM_SSE_ENDPOINT)
        .build();

    tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
    try {
        tomcat.start();

        // Log dello stato iniziale
        System.out.println("Tomcat server state after start(): " + tomcat.getServer().getState());
        System.out.println("Connector state after start(): " + tomcat.getConnector().getState());

        // Polling con timeout per assicurarsi che il connettore sia pronto
        int retries = 50;
        while (retries-- > 0 && tomcat.getConnector().getState() != LifecycleState.STARTED) {
            Thread.sleep(100);
        }
        if (tomcat.getConnector().getState() != LifecycleState.STARTED) {
            throw new IllegalStateException("Tomcat did not reach STARTED state");
        }

        // Health check HTTP
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + PORT + "/").openConnection();
            connection.setRequestMethod("GET");
            int responseCode = connection.getResponseCode();
            System.out.println("Health check response code: " + responseCode);
        } catch (IOException e) {
            System.err.println("Health check failed: " + e.getMessage());
        }

        assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
    }
    catch (Exception e) {
        throw new RuntimeException("Failed to start Tomcat", e);
    }

    clientBuilders.put("httpclient",
        McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
            .sseEndpoint(CUSTOM_SSE_ENDPOINT)
            .build()).requestTimeout(Duration.ofHours(10)));
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
