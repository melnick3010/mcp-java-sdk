/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.assertj.core.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpError;

/**
 * Tests for completion functionality with context support.
 *
 * @author Surbhi Bansal
 */
class McpCompletionTests {

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	McpClient.SyncSpec clientBuilder;

	private Tomcat tomcat;

	@BeforeEach
	public void before() {
		// Create and con figure the transport provider
		mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
				.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT).build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		this.clientBuilder = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT).build());
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
				e.printStackTrace();
			}
		}
	}

	@Test
	void testCompletionHandlerReceivesContext() {
		AtomicReference<CompleteRequest> receivedRequest = new AtomicReference<>();
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (exchange, request) -> {
			receivedRequest.set(request);
			return new CompleteResult(
					new CompleteResult.CompleteCompletion(Collections.singletonList("test-completion"), 1, false));
		};

		ResourceReference resourceRef = new ResourceReference(ResourceReference.TYPE, "test://resource/{param}");

		Resource resource = Resource.builder().uri("test://resource/{param}").name("Test Resource")
				.description("A resource for testing").mimeType("text/plain").size(123L).build();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
				.capabilities(ServerCapabilities.builder().completions().build())
				.resources(new McpServerFeatures.SyncResourceSpecification(resource,
						(exchange, req) -> new ReadResourceResult(Collections.emptyList())))
				.completions(new McpServerFeatures.SyncCompletionSpecification(resourceRef, completionHandler)).build();

		try (McpSyncClient mcpClient = clientBuilder
				.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0")).build();) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Test with context
			CompleteRequest request = new CompleteRequest(resourceRef,
					new CompleteRequest.CompleteArgument("param", "test"), null,
					new CompleteRequest.CompleteContext(Collections.singletonMap("previous", "value")));

			CompleteResult result = mcpClient.completeCompletion(request);

			// Verify handler received the context
			assertThat(receivedRequest.get().getContext()).isNotNull();
			assertThat(receivedRequest.get().getContext().getArguments()).containsEntry("previous", "value");
			assertThat(result.getCompletion().getValues()).containsExactly("test-completion");
		}

		mcpServer.close();
	}

	@Test
	void testCompletionBackwardCompatibility() {
		AtomicReference<Boolean> contextWasNull = new AtomicReference<>(false);
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (exchange, request) -> {
			contextWasNull.set(request.getContext() == null);
			return new CompleteResult(new CompleteResult.CompleteCompletion(
					Collections.singletonList("no-context-completion"), 1, false));
		};

		McpSchema.Prompt prompt = new Prompt("test-prompt", "this is a test prompt",
				Collections.singletonList(new PromptArgument("arg", "string", false)));

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
				.capabilities(ServerCapabilities.builder().completions().build())
				.prompts(new McpServerFeatures.SyncPromptSpecification(prompt,
						(mcpSyncServerExchange, getPromptRequest) -> null))
				.completions(new McpServerFeatures.SyncCompletionSpecification(
						new PromptReference(PromptReference.TYPE, "test-prompt"), completionHandler))
				.build();

		try (McpSyncClient mcpClient = clientBuilder
				.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0")).build();) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Test without context
			CompleteRequest request = new CompleteRequest(new PromptReference(PromptReference.TYPE, "test-prompt"),
					new CompleteRequest.CompleteArgument("arg", "val"));

			CompleteResult result = mcpClient.completeCompletion(request);

			// Verify context was null
			assertThat(contextWasNull.get()).isTrue();
			assertThat(result.getCompletion().getValues()).containsExactly("no-context-completion");
		}

		mcpServer.close();
	}

	@Test
	void testDependentCompletionScenario() {
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (exchange, request) -> {
			// Simulate database/table completion scenario
			if (request.getRef() instanceof ResourceReference) {
				ResourceReference resourceRef = (ResourceReference) request.getRef();
				if ("db://{database}/{table}".equals(resourceRef.getUri())) {
					if ("database".equals(request.getArgument().getName())) {
						// Complete database names
						return new CompleteResult(new CompleteResult.CompleteCompletion(
								java.util.Arrays.asList("users_db", "products_db", "analytics_db"), 3, false));
					}
					else if ("table".equals(request.getArgument().getName())) {
						// Complete table names based on selected database
						if (request.getContext() != null && request.getContext().getArguments() != null) {
							String db = request.getContext().getArguments().get("database");
							if ("users_db".equals(db)) {
								return new CompleteResult(new CompleteResult.CompleteCompletion(
										java.util.Arrays.asList("users", "sessions", "permissions"), 3, false));
							}
							else if ("products_db".equals(db)) {
								return new CompleteResult(new CompleteResult.CompleteCompletion(
										java.util.Arrays.asList("products", "categories", "inventory"), 3, false));
							}
						}
					}
				}
			}
			return new CompleteResult(new CompleteResult.CompleteCompletion(Collections.emptyList(), 0, false));
		};

		McpSchema.Resource resource = Resource.builder().uri("db://{database}/{table}").name("Database Table")
				.description("Resource representing a table in a database").mimeType("application/json").size(456L)
				.build();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
				.capabilities(ServerCapabilities.builder().completions().build())
				.resources(new McpServerFeatures.SyncResourceSpecification(resource,
						(exchange, req) -> new ReadResourceResult(Collections.emptyList())))
				.completions(new McpServerFeatures.SyncCompletionSpecification(
						new ResourceReference(ResourceReference.TYPE, "db://{database}/{table}"), completionHandler))
				.build();

		try (McpSyncClient mcpClient = clientBuilder
				.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0")).build();) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// First, complete database
			CompleteRequest dbRequest = new CompleteRequest(
					new ResourceReference(ResourceReference.TYPE, "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("database", ""));

			CompleteResult dbResult = mcpClient.completeCompletion(dbRequest);
			assertThat(dbResult.getCompletion().getValues()).contains("users_db", "products_db");

			// Then complete table with database context
			CompleteRequest tableRequest = new CompleteRequest(
					new ResourceReference(ResourceReference.TYPE, "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("table", ""),
					new CompleteRequest.CompleteContext(Collections.singletonMap("database", "users_db")));

			CompleteResult tableResult = mcpClient.completeCompletion(tableRequest);
			assertThat(tableResult.getCompletion().getValues()).containsExactly("users", "sessions", "permissions");

			// Different database gives different tables
			CompleteRequest tableRequest2 = new CompleteRequest(
					new ResourceReference(ResourceReference.TYPE, "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("table", ""),
					new CompleteRequest.CompleteContext(Collections.singletonMap("database", "products_db")));

			CompleteResult tableResult2 = mcpClient.completeCompletion(tableRequest2);
			assertThat(tableResult2.getCompletion().getValues()).containsExactly("products", "categories", "inventory");
		}

		mcpServer.close();
	}

	@Test
	void testCompletionErrorOnMissingContext() {
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (exchange, request) -> {
			if (request.getRef() instanceof ResourceReference) {
				ResourceReference resourceRef = (ResourceReference) request.getRef();
				if ("db://{database}/{table}".equals(resourceRef.getUri())) {
					if ("table".equals(request.getArgument().getName())) {
						// Check if database context is provided
						if (request.getContext() == null || request.getContext().getArguments() == null
								|| !request.getContext().getArguments().containsKey("database")) {

							throw McpError.builder(ErrorCodes.INVALID_REQUEST)
									.message("Please select a database first to see available tables").build();
						}
						// Normal completion if context is provided
						String db = request.getContext().getArguments().get("database");
						if ("test_db".equals(db)) {
							return new CompleteResult(new CompleteResult.CompleteCompletion(
									java.util.Arrays.asList("users", "orders", "products"), 3, false));
						}
					}
				}
			}
			return new CompleteResult(new CompleteResult.CompleteCompletion(Collections.emptyList(), 0, false));
		};

		McpSchema.Resource resource = Resource.builder().uri("db://{database}/{table}").name("Database Table")
				.description("Resource representing a table in a database").mimeType("application/json").size(456L)
				.build();

		McpSyncServer mcpServer = McpServer.sync(mcpServerTransportProvider)
				.capabilities(ServerCapabilities.builder().completions().build())
				.resources(new McpServerFeatures.SyncResourceSpecification(resource,
						(exchange, req) -> new ReadResourceResult(Collections.emptyList())))
				.completions(new McpServerFeatures.SyncCompletionSpecification(
						new ResourceReference(ResourceReference.TYPE, "db://{database}/{table}"), completionHandler))
				.build();

		try (McpSyncClient mcpClient = clientBuilder
				.clientInfo(new McpSchema.Implementation("Sample" + "client", "0.0.0")).build();) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Try to complete table without database context - should raise error
			CompleteRequest requestWithoutContext = new CompleteRequest(
					new ResourceReference(ResourceReference.TYPE, "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("table", ""));

			assertThatExceptionOfType(McpError.class)
					.isThrownBy(() -> mcpClient.completeCompletion(requestWithoutContext))
					.withMessageContaining("Please select a database first");

			// Now complete with proper context - should work normally
			CompleteRequest requestWithContext = new CompleteRequest(
					new ResourceReference(ResourceReference.TYPE, "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("table", ""),
					new CompleteRequest.CompleteContext(Collections.singletonMap("database", "test_db")));

			CompleteResult resultWithContext = mcpClient.completeCompletion(requestWithContext);
			assertThat(resultWithContext.getCompletion().getValues()).containsExactly("users", "orders", "products");
		}

		mcpServer.close();
	}

}