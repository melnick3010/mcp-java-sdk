/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.ProtocolVersions;
import net.javacrumbs.jsonunit.core.Option;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport.APPLICATION_JSON;
import static io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport.TEXT_EVENT_STREAM;
import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(15)
class HttpServletStatelessIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private HttpServletStatelessServerTransport mcpStatelessServerTransport;

	ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	private Tomcat tomcat;

	@BeforeEach
	public void before() {
		this.mcpStatelessServerTransport = HttpServletStatelessServerTransport.builder()
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpStatelessServerTransport);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build()).initializationTimeout(Duration.ofHours(10)).requestTimeout(Duration.ofHours(10)));
	}

	@AfterEach
	public void after() {
		if (mcpStatelessServerTransport != null) {
			mcpStatelessServerTransport.closeGracefully().block();
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

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testToolCallSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		CallToolResult callResponse = CallToolResult.builder()
			.content(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")))
			.isError(false)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool1 = new McpStatelessServerFeatures.SyncToolSpecification(
				Tool.builder().name("tool1").title("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build(),
				(transportContext, request) -> {
					// chiamata HTTP bloccante con Apache HttpClient (Java 8)
					try (org.apache.http.impl.client.CloseableHttpClient httpClient = org.apache.http.impl.client.HttpClients
						.createDefault()) {

						org.apache.http.client.methods.HttpGet get = new org.apache.http.client.methods.HttpGet(
								"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md");

						try (org.apache.http.client.methods.CloseableHttpResponse resp = httpClient.execute(get)) {
							org.apache.http.HttpEntity entity = resp.getEntity();
							String response = entity != null ? org.apache.http.util.EntityUtils.toString(entity,
									java.nio.charset.StandardCharsets.UTF_8) : "";

							assertThat(response).isNotBlank();
						}
					}
					catch (java.io.IOException e) {
						throw new RuntimeException("HTTP call failed", e);
					}

					return callResponse;
				});

		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().getTools()).contains(tool1.tool());

			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response).isEqualTo(callResponse);
		}
		finally {
			mcpServer.close();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testInitialize(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}
		finally {
			mcpServer.close();
		}
	}

	// ---------------------------------------
	// Completion Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : Completion call")
	@ValueSource(strings = { "httpclient" })
	void testCompletionShouldReturnExpectedSuggestions(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<String> expectedValues = Arrays.asList("python", "pytorch", "pyside");
		CompleteResult completionResponse = new CompleteResult(new CompleteResult.CompleteCompletion(expectedValues, 10, // total
				true // hasMore
		));

		AtomicReference<CompleteRequest> samplingRequest = new AtomicReference<>();
		BiFunction<McpTransportContext, CompleteRequest, CompleteResult> completionHandler = (transportContext,
				request) -> {
			samplingRequest.set(request);
			return completionResponse;
		};

		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.capabilities(ServerCapabilities.builder().completions().build())
			.prompts(
					new McpStatelessServerFeatures.SyncPromptSpecification(
							new Prompt("code_review", "Code review", "this is code review prompt",
									Collections
										.singletonList(new PromptArgument("language", "Language", "string", false))),
							(transportContext, getPromptRequest) -> null))
			.completions(new McpStatelessServerFeatures.SyncCompletionSpecification(
					new PromptReference(PromptReference.TYPE, "code_review", "Code review"), completionHandler))
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CompleteRequest request = new CompleteRequest(
					new PromptReference(PromptReference.TYPE, "code_review", "Code review"),
					new CompleteRequest.CompleteArgument("language", "py"));

			CompleteResult result = mcpClient.completeCompletion(request);

			assertThat(result).isNotNull();

			assertThat(samplingRequest.get().getArgument().getName()).isEqualTo("language");
			assertThat(samplingRequest.get().getArgument().getValue()).isEqualTo("py");
			assertThat(samplingRequest.get().getRef().type()).isEqualTo(PromptReference.TYPE);
		}
		finally {
			mcpServer.close();
		}
	}

	// ---------------------------------------
	// Tool Structured Output Schema Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputValidationSuccess(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema

		Map<String, Object> outputSchema = new LinkedHashMap<String, Object>();

		outputSchema.put("type", "object");

		// Proprietà interne
		Map<String, Object> propertiesMap = new LinkedHashMap<String, Object>();

		Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
		resultMap.put("type", "number");
		propertiesMap.put("result", resultMap);

		Map<String, Object> operationMap = new LinkedHashMap<String, Object>();
		operationMap.put("type", "string");
		propertiesMap.put("operation", operationMap);

		Map<String, Object> timestampMap = new LinkedHashMap<String, Object>();
		timestampMap.put("type", "string");
		propertiesMap.put("timestamp", timestampMap);

		outputSchema.put("properties", propertiesMap);

		// Lista required
		outputSchema.put("required", Arrays.asList("result", "operation"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool = new McpStatelessServerFeatures.SyncToolSpecification(
				calculatorTool, (transportContext, request) -> {
					String expression = (String) request.getArguments().getOrDefault("expression", "2 + 3");
					double result = evaluateExpression(expression);

					// Costruzione della mappa structuredContent compatibile Java 8
					Map<String, Object> structuredContent = new LinkedHashMap<String, Object>();
					structuredContent.put("result", result);
					structuredContent.put("operation", expression);
					structuredContent.put("timestamp", "2024-01-01T10:00:00Z");

					return CallToolResult.builder().structuredContent(structuredContent).build();
				});

		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Verify tool is listed with output schema
			ListToolsResult toolsList = mcpClient.listTools();
			assertThat(toolsList.getTools()).hasSize(1);
			assertThat(toolsList.getTools().get(0).getName()).isEqualTo("calculator");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call tool with valid structured output
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Collections.singletonMap("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isFalse();
			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);

			assertThatJson(((McpSchema.TextContent) response.getContent().get(0)).getText())
				.when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("{\"result\":5.0,\"operation\":\"2 + 3\",\"timestamp\":\"2024-01-01T10:00:00Z\"}"));

			assertThat(response.getStructuredContent()).isNotNull();
			assertThatJson(response.getStructuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("{\"result\":5.0,\"operation\":\"2 + 3\",\"timestamp\":\"2024-01-01T10:00:00Z\"}"));
		}
		finally {
			mcpServer.close();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputOfObjectArrayValidationSuccess(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema that returns an array of objects
		Map<String, Object> outputSchema = new LinkedHashMap<String, Object>(); // mantiene
																				// l'ordine
																				// delle
																				// chiavi
		// @formatter:off
		outputSchema.put("type", "array");

		// items: object con properties e required
		Map<String, Object> items = new LinkedHashMap<String, Object>();
		items.put("type", "object");

		// properties: name:string, age:number
		Map<String, Object> properties = new LinkedHashMap<String, Object>();

		Map<String, Object> nameSchema = new LinkedHashMap<String, Object>();
		nameSchema.put("type", "string");
		properties.put("name", nameSchema);

		Map<String, Object> ageSchema = new LinkedHashMap<String, Object>();
		ageSchema.put("type", "number");
		properties.put("age", ageSchema);

		items.put("properties", properties);

		// required: ["name", "age"]
		items.put("required", Arrays.asList("name", "age"));

		outputSchema.put("items", items);
		// @formatter:on

		Tool calculatorTool = Tool.builder()
			.name("getMembers")
			.description("Returns a list of members")
			.outputSchema(outputSchema)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
			.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> {
				// Costruzione della lista di mappe compatibile Java 8
				Map<String, Object> person1 = new LinkedHashMap<String, Object>();
				person1.put("name", "John");
				person1.put("age", 30);

				Map<String, Object> person2 = new LinkedHashMap<String, Object>();
				person2.put("name", "Peter");
				person2.put("age", 25);

				return CallToolResult.builder().structuredContent(Arrays.asList(person1, person2)).build();
			})
			.build();

		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			assertThat(mcpClient.initialize()).isNotNull();

			// Call tool with valid structured output of type array
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("getMembers", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isFalse();

			assertThat(response.getStructuredContent()).isNotNull();
			assertThatJson(response.getStructuredContent()).when(Option.IGNORING_ARRAY_ORDER)
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isArray()
				.hasSize(2)
				.containsExactlyInAnyOrder(json("{\"name\":\"John\",\"age\":30}"),
						json("{\"name\":\"Peter\",\"age\":25}"));
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputWithInHandlerError(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema

		Map<String, Object> outputSchema = new LinkedHashMap<String, Object>();

		outputSchema.put("type", "object");

		// Proprietà interne
		Map<String, Object> propertiesMap = new LinkedHashMap<String, Object>();

		Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
		resultMap.put("type", "number");
		propertiesMap.put("result", resultMap);

		Map<String, Object> operationMap = new LinkedHashMap<String, Object>();
		operationMap.put("type", "string");
		propertiesMap.put("operation", operationMap);

		Map<String, Object> timestampMap = new LinkedHashMap<String, Object>();
		timestampMap.put("type", "string");
		propertiesMap.put("timestamp", timestampMap);

		outputSchema.put("properties", propertiesMap);

		// Lista required
		outputSchema.put("required", Arrays.asList("result", "operation"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		// Handler that returns an error result
		McpStatelessServerFeatures.SyncToolSpecification tool = McpStatelessServerFeatures.SyncToolSpecification
			.builder()
			.tool(calculatorTool)
			.callHandler((exchange, request) -> CallToolResult.builder()
				.isError(true)
				.content(Collections.singletonList(new TextContent("Error calling tool: Simulated in-handler error")))
				.build())
			.build();

		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Verify tool is listed with output schema
			ListToolsResult toolsList = mcpClient.listTools();
			assertThat(toolsList.getTools()).hasSize(1);
			assertThat(toolsList.getTools().get(0).getName()).isEqualTo("calculator");
			// Note: outputSchema might be null in sync server, but validation still works

			// Call tool with valid structured output
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Collections.singletonMap("expression", "2 + 3")));

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
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputValidationFailure(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = new LinkedHashMap<String, Object>();

		outputSchema.put("type", "object");

		// Proprietà interne
		Map<String, Object> propertiesMap = new LinkedHashMap<String, Object>();

		Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
		resultMap.put("type", "number");
		propertiesMap.put("result", resultMap);

		Map<String, Object> operationMap = new LinkedHashMap<String, Object>();
		operationMap.put("type", "string");
		propertiesMap.put("operation", operationMap);

		outputSchema.put("properties", propertiesMap);

		// Lista required
		outputSchema.put("required", Arrays.asList("result", "operation"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool = new McpStatelessServerFeatures.SyncToolSpecification(
				calculatorTool, (transportContext, request) -> {
					// Return invalid structured output. Result should be number, missing
					// operation
					Map<String, String> structuredContent = new HashMap<>();
					structuredContent.put("result", "not-a-number");
					structuredContent.put("extra", "field");

					return CallToolResult.builder()
						.addTextContent("Invalid calculation")
						.structuredContent(structuredContent)
						.build();
				});

		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call tool with invalid structured output
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Collections.singletonMap("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isTrue();
			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);

			String errorMessage = ((McpSchema.TextContent) response.getContent().get(0)).getText();
			assertThat(errorMessage).contains("Validation failed");
		}
		finally {
			mcpServer.close();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputMissingStructuredContent(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = new HashMap<>();

		// Proprietà interne
		Map<String, Object> resultProperties = new HashMap<>();
		resultProperties.put("type", "number");

		// Mappa "properties"
		Map<String, Object> properties = new HashMap<>();
		properties.put("result", resultProperties);

		// Costruzione schema
		outputSchema.put("type", "object");
		outputSchema.put("properties", properties);
		outputSchema.put("required", Collections.singletonList("result"));

		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.outputSchema(outputSchema)
			.build();

		McpStatelessServerFeatures.SyncToolSpecification tool = new McpStatelessServerFeatures.SyncToolSpecification(
				calculatorTool, (transportContext, request) -> {
					// Return result without structured content but tool has output schema
					return CallToolResult.builder().addTextContent("Calculation completed").build();
				});

		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.instructions("bla")
			.tools(tool)
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call tool that should return structured content but doesn't
			CallToolResult response = mcpClient
				.callTool(new McpSchema.CallToolRequest("calculator", Collections.singletonMap("expression", "2 + 3")));

			assertThat(response).isNotNull();
			assertThat(response.getIsError()).isTrue();
			assertThat(response.getContent()).hasSize(1);
			assertThat(response.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);

			String errorMessage = ((McpSchema.TextContent) response.getContent().get(0)).getText();
			assertThat(errorMessage).isEqualTo(
					"Response missing structured content which is expected when calling tool with non-empty outputSchema");
		}
		finally {
			mcpServer.close();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputRuntimeToolAddition(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Start server without tools
		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Initially no tools
			assertThat(mcpClient.listTools().getTools()).isEmpty();

			// Add tool with output schema at runtime
			Map<String, Object> outputSchema = new LinkedHashMap<String, Object>();

			outputSchema.put("type", "object");

			// Proprietà interne
			Map<String, Object> propertiesMap = new LinkedHashMap<String, Object>();

			Map<String, Object> messageMap = new LinkedHashMap<String, Object>();
			messageMap.put("type", "string");
			propertiesMap.put("message", messageMap);

			Map<String, Object> countMap = new LinkedHashMap<String, Object>();
			countMap.put("type", "integer");
			propertiesMap.put("count", countMap);

			outputSchema.put("properties", propertiesMap);

			// Lista required
			outputSchema.put("required", Arrays.asList("message", "count"));

			Tool dynamicTool = Tool.builder()
				.name("dynamic-tool")
				.description("Dynamically added tool")
				.outputSchema(outputSchema)
				.build();

			McpStatelessServerFeatures.SyncToolSpecification toolSpec = new McpStatelessServerFeatures.SyncToolSpecification(
					dynamicTool, (transportContext, request) -> {
						int count = (Integer) request.getArguments().getOrDefault("count", 1);

						Map<String, Object> structuredContent = new HashMap<>();
						structuredContent.put("message", "Dynamic execution");
						structuredContent.put("count", count);

						return CallToolResult.builder()
							.addTextContent("Dynamic tool executed " + count + " times")
							.structuredContent(structuredContent)
							.build();
					});

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
				.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
				.isObject()
				.isEqualTo(json("{\"count\":3,\"message\":\"Dynamic execution\"}"));
		}
		finally {
			mcpServer.close();
		}
	}

	@Test
	void testThrownMcpErrorAndJsonRpcError() throws Exception {
		McpStatelessSyncServer mcpServer = McpServer.sync(mcpStatelessServerTransport)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.build();

		Tool testTool = Tool.builder().name("test").description("test").build();

		McpStatelessServerFeatures.SyncToolSpecification toolSpec = new McpStatelessServerFeatures.SyncToolSpecification(
				testTool, (transportContext, request) -> {
					throw new RuntimeException("testing");
				});

		mcpServer.addTool(toolSpec);

		McpSchema.CallToolRequest callToolRequest = new McpSchema.CallToolRequest("test", Collections.emptyMap());
		McpSchema.JSONRPCRequest jsonrpcRequest = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_TOOLS_CALL, "test", callToolRequest);

		MockHttpServletRequest request = new MockHttpServletRequest("POST", CUSTOM_MESSAGE_ENDPOINT);
		MockHttpServletResponse response = new MockHttpServletResponse();

		byte[] content = JSON_MAPPER.writeValueAsBytes(jsonrpcRequest);
		request.setContent(content);
		request.addHeader("Content-Type", "application/json");
		request.addHeader("Content-Length", Integer.toString(content.length));
		request.addHeader("Content-Length", Integer.toString(content.length));
		request.addHeader("Accept", APPLICATION_JSON + ", " + TEXT_EVENT_STREAM);
		request.addHeader("Content-Type", APPLICATION_JSON);
		request.addHeader("Cache-Control", "no-cache");
		request.addHeader(HttpHeaders.PROTOCOL_VERSION, ProtocolVersions.MCP_2025_03_26);

		mcpStatelessServerTransport.service(request, response);

		McpSchema.JSONRPCResponse jsonrpcResponse = JSON_MAPPER.readValue(response.getContentAsByteArray(),
				McpSchema.JSONRPCResponse.class);

		assertThat(jsonrpcResponse).isNotNull();
		assertThat(jsonrpcResponse.error()).isNotNull();
		assertThat(jsonrpcResponse.error().code()).isEqualTo(ErrorCodes.INTERNAL_ERROR);
		assertThat(jsonrpcResponse.error().message()).isEqualTo("testing");

		mcpServer.close();
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
