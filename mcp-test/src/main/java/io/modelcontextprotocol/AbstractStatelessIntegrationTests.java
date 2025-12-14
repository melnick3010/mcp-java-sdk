/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.javacrumbs.jsonunit.core.Option;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

public abstract class AbstractStatelessIntegrationTests {

	protected ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	abstract protected void prepareClients(int port, String mcpEndpoint);

	abstract protected StatelessAsyncSpecification prepareAsyncServerBuilder();

	abstract protected StatelessSyncSpecification prepareSyncServerBuilder();

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void simple(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpStatelessAsyncServer server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(1000)).build();

		try (
				// Create client without sampling capabilities
				McpSyncClient client = clientBuilder
						.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
						.requestTimeout(Duration.ofSeconds(1000)).build()) {

			assertThat(client.initialize()).isNotNull();

		}
		finally {
			server.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testToolCallSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		CallToolResult callResponse = new McpSchema.CallToolResult(
				Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")), null);

		McpStatelessServerFeatures.SyncToolSpecification tool1 = McpStatelessServerFeatures.SyncToolSpecification
				.builder().tool(Tool.builder().name("tool1").description("tool1 description")
						.inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((ctx, request) -> {

					try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
						HttpGet httpGet = new HttpGet(
								"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md");

						try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
							String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
							assertThat(responseBody).isNotBlank();
						}
					}
					catch (Exception e) {
						e.printStackTrace();
					}

					return callResponse;
				}).build();

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder()
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool1).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
// Compare tools by name instead of object identity
List<McpSchema.Tool> tools = mcpClient.listTools().getTools();
assertThat(tools).extracting(McpSchema.Tool::getName).contains(tool1.tool().getName());


			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(response).isNotNull().usingRecursiveComparison().isEqualTo(callResponse);
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testThrowingToolCallIsCaughtBeforeTimeout(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder()
				.capabilities(ServerCapabilities.builder().tools(true).build())
				.tools(McpStatelessServerFeatures.SyncToolSpecification.builder().tool(Tool.builder().name("tool1")
						.description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
						.callHandler((context, request) -> {
							// We trigger a timeout on blocking read, raising an exception
							Mono.never().block(Duration.ofSeconds(1));
							return null;
						}).build())
				.build();

		try (McpSyncClient mcpClient = clientBuilder.requestTimeout(Duration.ofMillis(6666)).build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// We expect the tool call to fail immediately with the exception raised by
			// the offending tool
			// instead of getting back a timeout.
			assertThatExceptionOfType(McpError.class)
					.isThrownBy(
							() -> mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap())))
					.withMessageContaining("Timeout on blocking read");
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testToolListChangeHandlingSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		CallToolResult callResponse = new McpSchema.CallToolResult(
				Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpStatelessServerFeatures.SyncToolSpecification tool1 = McpStatelessServerFeatures.SyncToolSpecification
				.builder().tool(Tool.builder().name("tool1").description("tool1 description")
						.inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((ctx, request) -> {
					// perform a blocking call to a remote service

					try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
						HttpGet httpGet = new HttpGet(
								"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md");

						try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
							// Leggi il corpo come stringa (UTF-8)
							String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
							assertThat(responseBody).isNotBlank();
						}
					}
					catch (Exception e) {
						e.printStackTrace();
					}

					return callResponse;
				}).build();

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder()
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool1).build();

		try (McpSyncClient mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
				HttpGet httpGet = new HttpGet(
						"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md");

				try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
					// Leggi il corpo come stringa (UTF-8)
					String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
					assertThat(responseBody).isNotBlank();
				}
			}
			catch (Exception e) {
				e.printStackTrace();
			}

			rootsRef.set(toolsUpdate);
		}).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();
// Compare tools by name instead of object identity
List<McpSchema.Tool> tools = mcpClient.listTools().getTools();
assertThat(tools).extracting(McpSchema.Tool::getName).contains(tool1.tool().getName());


			// Remove a tool
			mcpServer.removeTool("tool1");

			// Add a new tool
			McpStatelessServerFeatures.SyncToolSpecification tool2 = McpStatelessServerFeatures.SyncToolSpecification
					.builder().tool(Tool.builder().name("tool2").description("tool2 description")
							.inputSchema(EMPTY_JSON_SCHEMA).build())
					.callHandler((exchange, request) -> callResponse).build();

			mcpServer.addTool(tool2);
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testInitialize(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder().build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	// ---------------------------------------
	// Tool Structured Output Schema Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testStructuredOutputValidationSuccess(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> properties = new HashMap<>();
		properties.put("result", Collections.singletonMap("type", "number"));
		properties.put("operation", Collections.singletonMap("type", "string"));
		properties.put("timestamp", Collections.singletonMap("type", "string"));

		Map<String, Object> outputSchema = new HashMap<>();
		outputSchema.put("type", "object");
		outputSchema.put("properties", properties);
		outputSchema.put("required", Arrays.asList("result", "operation"));

		Tool calculatorTool = Tool.builder().name("calculator").description("Performs mathematical calculations")
				.outputSchema(outputSchema).build();

		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
				.builder().tool(calculatorTool).callHandler((exchange, request) -> {

					String expression = (String) request.getArguments().getOrDefault("expression", "2 + 3");
					double result = evaluateExpression(expression);
					Map<String, Object> structuredContent = new HashMap<>();
					structuredContent.put("result", result);
					structuredContent.put("operation", expression);
					structuredContent.put("timestamp", "2024-01-01T10:00:00Z");
					return CallToolResult.builder().structuredContent(structuredContent).build();
				}).build();

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Verify tool is listed with output schema
			ListToolsResult toolsList = mcpClient.listTools();
			assertThat(toolsList.getTools()).hasSize(1);
			assertThat(toolsList.getTools().get(0).getName()).isEqualTo("calculator");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call tool with valid structured output
			CallToolResult response = mcpClient.callTool(
					new McpSchema.CallToolRequest("calculator", Collections.singletonMap("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isFalse();

			// In WebMVC, structured content is returned properly
			if (response.getStructuredContent() != null) {
				assertThat((Map<String, Object>) response.getStructuredContent()).containsEntry("result", 5.0)
						.containsEntry("operation", "2 + 3").containsEntry("timestamp", "2024-01-01T10:00:00Z");
			}
			else {
				// Fallback to checking content if structured content is not available
				assertThat(response.getContent()).isNotEmpty();
			}

			assertThat(response.getStructuredContent()).isNotNull();
			assertThatJson(response.getStructuredContent()).when(Option.IGNORING_ARRAY_ORDER)
					.when(Option.IGNORING_EXTRA_ARRAY_ITEMS).isObject()
					.isEqualTo(json("{\"result\":5.0,\"operation\":\"2 + 3\",\"timestamp\":\"2024-01-01T10:00:00Z\"}"));
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testStructuredOutputOfObjectArrayValidationSuccess(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema that returns an array of objects
		Map<String, Object> properties = new HashMap<>();
		properties.put("name", Collections.singletonMap("type", "string"));
		properties.put("age", Collections.singletonMap("type", "number"));

		Map<String, Object> items = new HashMap<>();
		items.put("type", "object");
		items.put("properties", properties);
		items.put("required", Arrays.asList("name", "age"));

		Map<String, Object> outputSchema = new HashMap<>();
		outputSchema.put("type", "array");
		outputSchema.put("items", items);

		Tool calculatorTool = Tool.builder().name("getMembers").description("Returns a list of members")
				.outputSchema(outputSchema).build();

		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
				.builder().tool(calculatorTool).callHandler((exchange, request) -> {

					List<Map<String, Object>> people = Arrays.asList(new HashMap<String, Object>() {
						{
							put("name", "John");
							put("age", 30);
						}
					}, new HashMap<String, Object>() {
						{
							put("name", "Peter");
							put("age", 25);
						}
					});

					return CallToolResult.builder().structuredContent(people).build();
				}).build();

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			assertThat(mcpClient.initialize()).isNotNull();

			// Call tool with valid structured output of type array
			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("getMembers", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isFalse();

			assertThat(response.getStructuredContent()).isNotNull();
			assertThatJson(response.getStructuredContent()).when(Option.IGNORING_ARRAY_ORDER)
					.when(Option.IGNORING_EXTRA_ARRAY_ITEMS).isArray().hasSize(2).containsExactlyInAnyOrder(
							json("{\"name\":\"John\",\"age\":30}"), json("{\"name\":\"Peter\",\"age\":25}"));
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testStructuredOutputWithInHandlerError(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		Map<String, Object> properties = new HashMap<>();
		properties.put("result", Collections.singletonMap("type", "number"));
		properties.put("operation", Collections.singletonMap("type", "string"));

		Map<String, Object> outputSchema = new HashMap<>();
		outputSchema.put("type", "object");
		outputSchema.put("properties", properties);
		outputSchema.put("timestamp", Collections.singletonMap("type", "string"));
		outputSchema.put("type", Arrays.asList("result", "operation"));
		// Create a tool with output schema

		Tool calculatorTool = Tool.builder().name("calculator").description("Performs mathematical calculations")
				.outputSchema(outputSchema).build();

		// Handler that throws an exception to simulate an error
		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
				.builder().tool(calculatorTool)
				.callHandler(
						(exchange, request) -> CallToolResult.builder().isError(true)
								.content(Collections.singletonList(
										new TextContent("Error calling tool: Simulated in-handler error")))
								.build())
				.build();

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Verify tool is listed with output schema
			ListToolsResult toolsList = mcpClient.listTools();
			assertThat(toolsList.getTools()).hasSize(1);
			assertThat(toolsList.getTools().get(0).getName()).isEqualTo("calculator");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call tool with valid structured output
			CallToolResult response = mcpClient.callTool(
					new McpSchema.CallToolRequest("calculator", Collections.singletonMap("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isTrue();
			assertThat(response.getContent()).isNotEmpty();
			assertThat(response.getContent())
					.containsExactly(new McpSchema.TextContent("Error calling tool: Simulated in-handler error"));
			assertThat(response.getStructuredContent()).isNull();
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testStructuredOutputValidationFailure(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> properties = new HashMap<>();
		properties.put("result", Collections.singletonMap("type", "number"));
		properties.put("operation", Collections.singletonMap("type", "string"));

		Map<String, Object> outputSchema = new HashMap<>();
		outputSchema.put("type", "object");
		outputSchema.put("properties", properties);
		outputSchema.put("required", Arrays.asList("result", "operation"));

		Tool calculatorTool = Tool.builder().name("calculator").description("Performs mathematical calculations")
				.outputSchema(outputSchema).build();

		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
				.builder().tool(calculatorTool).callHandler((exchange, request) -> {
					// Return invalid structured output. Result should be number, missing
					// operation
					Map<String, Object> structuredContent = new HashMap<>();
					outputSchema.put("result", "not-a-number");
					outputSchema.put("extra", "field");
					return CallToolResult.builder().addTextContent("Invalid calculation")
							.structuredContent(structuredContent).build();
				}).build();

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call tool with invalid structured output
			CallToolResult response = mcpClient.callTool(
					new McpSchema.CallToolRequest("calculator", Collections.singletonMap("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isTrue();
			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);

			String errorMessage = ((McpSchema.TextContent) response.getContent().get(0)).getText();
			assertThat(errorMessage).contains("Validation failed");
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testStructuredOutputMissingStructuredContent(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema

		// Create outputSchema in Java 8 style
		Map<String, Object> resultProperty = new HashMap<>();
		resultProperty.put("type", "number");

		Map<String, Object> properties = new HashMap<>();
		properties.put("result", resultProperty);

		Map<String, Object> outputSchema = new HashMap<>();
		outputSchema.put("type", "object");
		outputSchema.put("properties", properties);
		outputSchema.put("required", Arrays.asList("result"));

		Tool calculatorTool = Tool.builder().name("calculator").description("Performs mathematical calculations")
				.outputSchema(outputSchema).build();

		SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification.builder().tool(calculatorTool)
				.callHandler((exchange, request) -> {
					// Return result without structured content but tool has output schema
					return CallToolResult.builder().addTextContent("Calculation completed").build();
				}).build();

		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call tool that should return structured content but doesn't
			CallToolResult response = mcpClient.callTool(
					new McpSchema.CallToolRequest("calculator", Collections.singletonMap("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isTrue();
			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);

			String errorMessage = ((McpSchema.TextContent) response.getContent().get(0)).getText();
			assertThat(errorMessage).isEqualTo(
					"Response missing structured content which is expected when calling tool with non-empty outputSchema");
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testStructuredOutputRuntimeToolAddition(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Start server without tools
		McpStatelessSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Initially no tools
			assertThat(mcpClient.listTools().getTools()).isEmpty();

			// Add tool with output schema at runtime

			// Create outputSchema in Java 8 style
			Map<String, Object> messageProperty = new HashMap<>();
			messageProperty.put("type", "string");

			Map<String, Object> countProperty = new HashMap<>();
			countProperty.put("type", "integer");

			Map<String, Object> properties = new HashMap<>();
			properties.put("message", messageProperty);
			properties.put("count", countProperty);

			Map<String, Object> outputSchema = new HashMap<>();
			outputSchema.put("type", "object");
			outputSchema.put("properties", properties);
			outputSchema.put("required", Arrays.asList("message", "count"));

			Tool dynamicTool = Tool.builder().name("dynamic-tool").description("Dynamically added tool")
					.outputSchema(outputSchema).build();

			SyncToolSpecification toolSpec = McpStatelessServerFeatures.SyncToolSpecification.builder()
					.tool(dynamicTool).callHandler((exchange, request) -> {
						int count = (Integer) request.getArguments().getOrDefault("count", 1);

						Map<String, Object> structuredContent = new HashMap<>();
						structuredContent.put("message", "Dynamic execution");
						structuredContent.put("count", count);

						return CallToolResult.builder().addTextContent("Dynamic tool executed " + count + " times")
								.structuredContent(structuredContent).build();
					}).build();

			// Add tool to server
			mcpServer.addTool(toolSpec);

			// Wait for tool list change notification
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				assertThat(mcpClient.listTools().getTools()).hasSize(1);
			});

			// Verify tool was added with output schema
			ListToolsResult toolsList = mcpClient.listTools();
			assertThat(toolsList.getTools()).hasSize(1);
			assertThat(toolsList.getTools().get(0).getName()).isEqualTo("dynamic-tool");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call dynamically added tool
			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("dynamic-tool", Collections.singletonMap("count", 3)));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isFalse();

			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) response.getContent().get(0)).getText())
					.isEqualTo("Dynamic tool executed 3 times");

			assertThat(response.getStructuredContent()).isNotNull();
			assertThatJson(response.getStructuredContent()).when(Option.IGNORING_ARRAY_ORDER)
					.when(Option.IGNORING_EXTRA_ARRAY_ITEMS).isObject()
					.isEqualTo(json("{\"count\":3,\"message\":\"Dynamic execution\"}"));
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	private double evaluateExpression(String expression) {
		// Simple expression evaluator for testing
		switch (expression) {
		case "2 + 3":
			return 5.0;
		case "10 * 2":
			return 20.0;
		case "7 + 8":
			return 15.0;
		case "5 + 3":
			return 8.0;
		default:
			return 0.0;
		}
	}

}
