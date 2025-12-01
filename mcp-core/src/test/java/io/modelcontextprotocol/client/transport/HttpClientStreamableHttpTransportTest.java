/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link HttpClientStreamableHttpTransport} class.
 *
 * @author Daniel Garnier-Moiroux
 */
class HttpClientStreamableHttpTransportTest {

	static String host = "http://localhost:3001";

	private McpTransportContext context = McpTransportContext
		.create(Collections.singletonMap("test-transport-context-key", "some-value"));

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
		.withCommand("node dist/index.js streamableHttp")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	@BeforeAll
	static void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@AfterAll
	static void stopContainer() {
		container.stop();
	}

	void withTransport(HttpClientStreamableHttpTransport transport, Consumer<HttpClientStreamableHttpTransport> c) {
		try {
			c.accept(transport);
		}
		finally {
			StepVerifier.create(transport.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void testRequestCustomizer() throws URISyntaxException {
		URI uri = new URI(host + "/mcp");
		McpSyncHttpClientRequestCustomizer mockRequestCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);

		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(host)
			.httpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			InitializeRequest initializeRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
					McpSchema.ClientCapabilities.builder().roots(true).build(),
					new McpSchema.Implementation("MCP Client", "0.3.1"));
			JSONRPCRequest testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
					McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

			StepVerifier
				.create(t.sendMessage(testMessage).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
				.verifyComplete();

			// Verify the customizer was called
			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(uri), eq(
					"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"MCP Client\",\"version\":\"0.3.1\"}}}"),
					eq(context));
		});
	}

	@Test
	void testAsyncRequestCustomizer() throws URISyntaxException {
		URI uri = new URI(host + "/mcp");
		McpAsyncHttpClientRequestCustomizer mockRequestCustomizer = mock(McpAsyncHttpClientRequestCustomizer.class);
		when(mockRequestCustomizer.customize(any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> Mono.just(invocation.getArguments()[0]));

		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(host)
			.asyncHttpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			InitializeRequest initializeRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
					McpSchema.ClientCapabilities.builder().roots(true).build(),
					new McpSchema.Implementation("MCP Client", "0.3.1"));
			JSONRPCRequest testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
					McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

			StepVerifier
				.create(t.sendMessage(testMessage).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
				.verifyComplete();

			// Verify the customizer was called
			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(uri), eq(
					"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"MCP Client\",\"version\":\"0.3.1\"}}}"),
					eq(context));
		});
	}

	@Test
	void testCloseUninitialized() {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(host).build();

		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		InitializeRequest initializeRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("MCP Client", "0.3.1"));
		JSONRPCRequest testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMessage("MCP session has been closed")
			.verify();
	}

	@Test
	void testCloseInitialized() {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(host).build();

		InitializeRequest initializeRequest = new McpSchema.InitializeRequest(McpSchema.LATEST_PROTOCOL_VERSION,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("MCP Client", "0.3.1"));
		JSONRPCRequest testMessage = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();
		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMatches(err -> err.getMessage().matches("MCP session with ID [a-zA-Z0-9-]* has been closed"))
			.verify();
	}

}
