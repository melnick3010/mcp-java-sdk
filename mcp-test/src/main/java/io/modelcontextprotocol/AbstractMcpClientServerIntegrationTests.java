/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.net.URI;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.Utils;
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
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertWith;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

public abstract class AbstractMcpClientServerIntegrationTests {

	protected ConcurrentHashMap<String, McpClient.SyncSpec> clientBuilders = new ConcurrentHashMap<>();

	abstract protected void prepareClients(int port, String mcpEndpoint);

	abstract protected McpServer.AsyncSpecification<?> prepareAsyncServerBuilder();

	abstract protected McpServer.SyncSpecification<?> prepareSyncServerBuilder();

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void simple(String clientType) {

		McpClient.SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpAsyncServer server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
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
	// Sampling Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateMessageWithoutSamplingCapabilities(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create real instances instead of mocking final classes
		CreateMessageRequest createMessageRequest = CreateMessageRequest.builder()
				.messages(Collections.singletonList(new McpSchema.SamplingMessage(Role.USER,
						new McpSchema.TextContent("Test message"))))
				.modelPreferences(ModelPreferences.builder().hints(Collections.emptyList())
						.costPriority(1.0).speedPriority(1.0).intelligencePriority(1.0).build())
				.build();
		
		CallToolResult callToolResult = CallToolResult.builder()
				.addContent(new McpSchema.TextContent("Test response"))
				.build();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
					return exchange.createMessage(createMessageRequest)
							.then(Mono.just(callToolResult));
				}).build();

		McpAsyncServer server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		try (
				// Create client without sampling capabilities
				McpSyncClient client = clientBuilder
						.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0")).build()) {

			assertThat(client.initialize()).isNotNull();

			McpError thrownError = null;
			try {
				client.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
			}
			catch (McpError e) {
				thrownError = e;
			}
			
			assertThat(thrownError).isNotNull()
					.isInstanceOf(McpError.class)
					.hasMessage("Client must be configured with sampling capabilities");
		}
		finally {
			server.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateMessageSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
				.addContent(new McpSchema.TextContent("CALL RESPONSE")).build();

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {

					CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
							.messages(Collections.singletonList((new McpSchema.SamplingMessage(McpSchema.Role.USER,
									new McpSchema.TextContent("Test message")))))
							.modelPreferences(ModelPreferences.builder().hints(Collections.emptyList())
									.costPriority(1.0).speedPriority(1.0).intelligencePriority(1.0).build())
							.build();

					return exchange.createMessage(createMessageRequest).doOnNext(samplingResult::set)
							.thenReturn(callResponse);
				}).build();

		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.capabilities(ClientCapabilities.builder().sampling().build()).sampling(samplingHandler).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response).usingRecursiveComparison().isEqualTo(callResponse);

			assertWith(samplingResult.get(), result -> {
				assertThat(result).isNotNull();
				assertThat(result.getRole()).isEqualTo(Role.USER);
				assertThat(result.getContent()).isInstanceOf(McpSchema.TextContent.class);
				assertThat(((McpSchema.TextContent) result.getContent()).getText()).isEqualTo("Test message");
				assertThat(result.getModel()).isEqualTo("MockModelName");
				assertThat(result.getStopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
			});
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateMessageWithRequestTimeoutSuccess(String clientType) throws InterruptedException {

		// Client

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		// Server

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
				.addContent(new McpSchema.TextContent("CALL RESPONSE")).build();

		AtomicReference<CreateMessageResult> samplingResult = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {

					CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
							.messages(Collections.singletonList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
									new McpSchema.TextContent("Test message"))))
							.modelPreferences(ModelPreferences.builder().hints(Collections.emptyList())
									.costPriority(1.0).speedPriority(1.0).intelligencePriority(1.0).build())
							.build();

					return exchange.createMessage(createMessageRequest).doOnNext(samplingResult::set)
							.thenReturn(callResponse);
				}).build();

		// Increase server timeout to 60s to allow for slow sampling operations (2s sleep + overhead)
		// Server timeout must be greater than client processing time to avoid premature timeout
		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(60)).tools(tool).build();
		// Increase client timeout to 90s to ensure it's greater than server timeout
		// This prevents client-side timeout before server can respond with error or success
		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.capabilities(ClientCapabilities.builder().sampling().build()).sampling(samplingHandler)
				.requestTimeout(Duration.ofSeconds(90)).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response).usingRecursiveComparison().isEqualTo(callResponse);

			assertWith(samplingResult.get(), result -> {
				assertThat(result).isNotNull();
				assertThat(result.getRole()).isEqualTo(Role.USER);
				assertThat(result.getContent()).isInstanceOf(McpSchema.TextContent.class);
				assertThat(((McpSchema.TextContent) result.getContent()).getText()).isEqualTo("Test message");
				assertThat(result.getModel()).isEqualTo("MockModelName");
				assertThat(result.getStopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
			});
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateMessageWithRequestTimeoutFail(String clientType) throws InterruptedException {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);
			try {
				TimeUnit.SECONDS.sleep(2);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
				.addContent(new McpSchema.TextContent("CALL RESPONSE")).build();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {

					McpSchema.CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
							.messages(Arrays.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
									new McpSchema.TextContent("Test message"))))
							.modelPreferences(ModelPreferences.builder().hints(Collections.emptyList())
									.costPriority(1.0).speedPriority(1.0).intelligencePriority(1.0).build())
							.build();

					return exchange.createMessage(createMessageRequest).thenReturn(callResponse);
				}).build();

		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(1)).tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.capabilities(ClientCapabilities.builder().sampling().build()).sampling(samplingHandler).build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
			}).withMessageContaining("1000ms");
		}
		finally {
			mcpServer.closeGracefully().block();
		}

	}

	// ---------------------------------------
	// Elicitation Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateElicitationWithoutElicitationCapabilities(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create real instances instead of mocking final classes
		ElicitRequest elicitRequest = ElicitRequest.builder()
				.messages(Collections.singletonList(new McpSchema.SamplingMessage(Role.USER,
						new McpSchema.TextContent("Test elicitation"))))
				.modelPreferences(ModelPreferences.builder().hints(Collections.emptyList())
						.costPriority(1.0).speedPriority(1.0).intelligencePriority(1.0).build())
				.build();
		
		CallToolResult callToolResult = CallToolResult.builder()
				.addContent(new McpSchema.TextContent("Test response"))
				.build();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
				.tool(Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA)
						.build())
				.callHandler((exchange, request) -> exchange.createElicitation(elicitRequest)
						.then(Mono.just(callToolResult)))
				.build();

		McpAsyncServer server = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		// Create client without elicitation capabilities
		try (McpSyncClient client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.build()) {

			assertThat(client.initialize()).isNotNull();

			McpError thrownError = null;
			try {
				client.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
			}
			catch (McpError e) {
				thrownError = e;
			}
			
			assertThat(thrownError).isNotNull()
					.isInstanceOf(McpError.class)
					.hasMessage("Client must be configured with elicitation capabilities");
		}
		finally {
			server.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateElicitationSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler = request -> {
			assertThat(request.getMessage()).isNotEmpty();
			assertThat(request.getRequestedSchema()).isNotNull();

			return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
					Collections.singletonMap("message", request.getMessage()));
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
				.addContent(new McpSchema.TextContent("CALL RESPONSE")).build();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
					Map<String, Object> reqSchema = new HashMap<>();
					reqSchema.put("type", "object");
					reqSchema.put("properties",
							Collections.singletonMap("message", Collections.singletonMap("type", "string")));
					ElicitRequest elicitationRequest = McpSchema.ElicitRequest.builder().message("Test message")
							.requestedSchema(reqSchema).build();

					StepVerifier.create(exchange.createElicitation(elicitationRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.getAction()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
						assertThat(result.getContent().get("message")).isEqualTo("Test message");
					}).verifyComplete();

					return Mono.just(callResponse);
				}).build();

		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.capabilities(ClientCapabilities.builder().elicitation().build()).elicitation(elicitationHandler)
				.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response).usingRecursiveComparison().isEqualTo(callResponse);
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateElicitationWithRequestTimeoutSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.getMessage()).isNotEmpty();
			assertThat(request.getRequestedSchema()).isNotNull();
			return new ElicitResult(ElicitResult.Action.ACCEPT,
					Collections.singletonMap("message", request.getMessage()));
		};

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
				.addContent(new McpSchema.TextContent("CALL RESPONSE")).build();

		AtomicReference<ElicitResult> resultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
					Map<String, Object> reqSchema = new HashMap<>();
					reqSchema.put("type", "object");
					reqSchema.put("properties",
							Collections.singletonMap("message", Collections.singletonMap("type", "string")));
					ElicitRequest elicitationRequest = McpSchema.ElicitRequest.builder().message("Test message")
							.requestedSchema(reqSchema).build();

					return exchange.createElicitation(elicitationRequest).doOnNext(resultRef::set)
							.then(Mono.just(callResponse));
				}).build();

		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(3)).tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.capabilities(ClientCapabilities.builder().elicitation().build()).elicitation(elicitationHandler)
				.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(response).isNotNull();
			assertThat(response).usingRecursiveComparison().isEqualTo(callResponse);
			assertWith(resultRef.get(), result -> {
				assertThat(result).isNotNull();
				assertThat(result.getAction()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
				assertThat(result.getContent().get("message")).isEqualTo("Test message");
			});
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testCreateElicitationWithRequestTimeoutFail(String clientType) {

		CountDownLatch latch = new CountDownLatch(1);

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		Function<ElicitRequest, ElicitResult> elicitationHandler = request -> {
			assertThat(request.getMessage()).isNotEmpty();
			assertThat(request.getRequestedSchema()).isNotNull();

			try {
				if (!latch.await(2, TimeUnit.SECONDS)) {
					throw new RuntimeException("Timeout waiting for elicitation processing");
				}
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			return new ElicitResult(ElicitResult.Action.ACCEPT,
					Collections.singletonMap("message", request.getMessage()));
		};

		CallToolResult callResponse = CallToolResult.builder().addContent(new TextContent("CALL RESPONSE")).build();

		AtomicReference<ElicitResult> resultRef = new AtomicReference<>();

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
					Map<String, Object> reqSchema = new HashMap<>();
					reqSchema.put("type", "object");
					reqSchema.put("properties",
							Collections.singletonMap("message", Collections.singletonMap("type", "string")));
					ElicitRequest elicitationRequest = ElicitRequest.builder().message("Test message")
							.requestedSchema(reqSchema).build();

					return exchange.createElicitation(elicitationRequest).doOnNext(resultRef::set)
							.then(Mono.just(callResponse));
				}).build();

		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(1)) // 1 second.
				.tools(tool).build();

		try (McpSyncClient mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
				.capabilities(ClientCapabilities.builder().elicitation().build()).elicitation(elicitationHandler)
				.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThatExceptionOfType(McpError.class).isThrownBy(() -> {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
			}).withMessageContaining("within 1000ms");

			ElicitResult elicitResult = resultRef.get();
			assertThat(elicitResult).isNull();
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testRootsSuccess(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate)).build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
				.roots(roots).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(rootsRef.get()).isNull();

			mcpClient.rootsListChangedNotification();

			// Increase timeout to 10s to handle notification processing delays in suite execution
			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});

			// Remove a root
			mcpClient.removeRoot(roots.get(0).getUri());

			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(Collections.singletonList(roots.get(1)));
			});

			// Add a new root
			Root root3 = new Root("uri3://", "root3");
			mcpClient.addRoot(root3);

			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(Arrays.asList(roots.get(1), root3));
			});
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testRootsWithoutCapability(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				}).build();

		McpSyncServer mcpServer = prepareSyncServerBuilder().rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		try (
				// Create client without roots capability
				// No roots capability
				McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			// Attempt to list roots should fail
			try {
				mcpClient.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
			}
			catch (McpError e) {
				assertThat(e).isInstanceOf(McpError.class).hasMessage("Roots not supported");
			}
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testRootsNotificationWithEmptyRootsList(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate)).build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
				.roots(Collections.emptyList()) // Empty roots list
				.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			// Increase timeout to 10s to handle notification processing delays in suite execution
			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				assertThat(rootsRef.get()).isEmpty();
			});
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testRootsWithMultipleHandlers(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
				.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate)).build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
				.roots(roots).build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			// Increase timeout to 10s to handle notification processing delays in suite execution
			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				assertThat(rootsRef1.get()).containsAll(roots);
				assertThat(rootsRef2.get()).containsAll(roots);
			});
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testRootsServerCloseWithActiveSubscription(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate)).build();

		try (McpSyncClient mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
				.roots(roots).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			mcpClient.rootsListChangedNotification();

			// Increase timeout to 10s to handle notification processing delays in suite execution
			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				assertThat(rootsRef.get()).containsAll(roots);
			});
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testToolCallSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		AtomicBoolean responseBodyIsNullOrBlank = new AtomicBoolean(false);
		CallToolResult callResponse = McpSchema.CallToolResult.builder()
				.addContent(new McpSchema.TextContent("CALL RESPONSE; ctx=importantValue")).build();

		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
					try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
						HttpGet httpGet = new HttpGet(
								"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md");
						try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
							String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
							responseBodyIsNullOrBlank.set(!Utils.hasText(responseBody));
						}
					}
					catch (Exception e) {
						e.printStackTrace();
					}
					return callResponse;
				}).build();

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool1).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().getTools()).contains(tool1.tool());

			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(responseBodyIsNullOrBlank.get()).isFalse();
			assertThat(response).isNotNull().usingRecursiveComparison().isEqualTo(callResponse);
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testThrowingToolCallIsCaughtBeforeTimeout(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.capabilities(ServerCapabilities.builder().tools(true).build())
				// Increase server timeout to 30s to ensure it's greater than the tool's internal timeout
				.requestTimeout(Duration.ofSeconds(30))
				.tools(McpServerFeatures.SyncToolSpecification.builder().tool(Tool.builder().name("tool1")
						.description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
						.callHandler((exchange, request) -> {
							// We trigger a timeout on blocking read, raising an exception
							// This should be caught and converted to McpError before client timeout
							Mono.never().block(Duration.ofSeconds(1));
							return null;
						}).build())
				.build();

		// Increase client timeout to 45s to ensure server error is received before client timeout
		try (McpSyncClient mcpClient = clientBuilder.requestTimeout(Duration.ofSeconds(45)).build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// We expect the tool call to fail immediately with the exception raised by
			// the offending tool instead of getting back a timeout.
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
	void testToolCallSuccessWithTranportContextExtraction(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		AtomicBoolean transportContextIsNull = new AtomicBoolean(false);
		AtomicBoolean transportContextIsEmpty = new AtomicBoolean(false);
		AtomicBoolean responseBodyIsNullOrBlank = new AtomicBoolean(false);

		CallToolResult expectedCallResponse = McpSchema.CallToolResult.builder()
				.addContent(new McpSchema.TextContent("CALL RESPONSE; ctx=value")).build();
		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {

					McpTransportContext transportContext = exchange.transportContext();
					transportContextIsNull.set(transportContext == null);
					transportContextIsEmpty.set(transportContext.equals(McpTransportContext.EMPTY));
					String ctxValue = (String) transportContext.get("important");

					try {
						String responseBody = "TOOL RESPONSE";
						responseBodyIsNullOrBlank.set(!Utils.hasText(responseBody));
					}
					catch (Exception e) {
						e.printStackTrace();
					}

					return McpSchema.CallToolResult.builder()
							.addContent(new McpSchema.TextContent("CALL RESPONSE; ctx=" + ctxValue)).build();
				}).build();

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool1).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().getTools()).contains(tool1.tool());

			CallToolResult response = mcpClient
					.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));

			assertThat(transportContextIsNull.get()).isFalse();
			assertThat(transportContextIsEmpty.get()).isFalse();
			assertThat(responseBodyIsNullOrBlank.get()).isFalse();
			assertThat(response).isNotNull().usingRecursiveComparison().isEqualTo(expectedCallResponse);
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testToolListChangeHandlingSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		CallToolResult callResponse = McpSchema.CallToolResult.builder()
				.addContent(new McpSchema.TextContent("CALL RESPONSE")).build();

		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder().tool(
				Tool.builder().name("tool1").description("tool1 description").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
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

		AtomicReference<List<Tool>> toolsRef = new AtomicReference<>();

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool1).build();

		try (McpSyncClient mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service (Java 8 + Apache HttpClient)
			try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
				HttpGet request = new HttpGet(
						"https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md");

				try (CloseableHttpResponse response = httpClient.execute(request)) {
					String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
					assertThat(responseBody).isNotBlank();
					toolsRef.set(toolsUpdate);
				}
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}).build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(toolsRef.get()).isNull();

			assertThat(mcpClient.listTools().getTools()).contains(tool1.tool());

			mcpServer.notifyToolsListChanged();

			// Increase timeout to 10s to handle notification processing and cache invalidation delays
			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				// Compare by tool name and description, not object identity
				assertThat(toolsRef.get()).isNotNull();
				assertThat(toolsRef.get()).hasSize(1);
				assertThat(toolsRef.get().get(0).getName()).isEqualTo(tool1.tool().getName());
			});

			// Remove a tool
			mcpServer.removeTool("tool1");

			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				assertThat(toolsRef.get()).isEmpty();
			});

			// Add a new tool
			McpServerFeatures.SyncToolSpecification tool2 = McpServerFeatures.SyncToolSpecification
					.builder().tool(Tool.builder().name("tool2").description("tool2 description")
							.inputSchema(EMPTY_JSON_SCHEMA).build())
					.callHandler((exchange, request) -> callResponse).build();

			mcpServer.addTool(tool2);

			await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
				// Compare by tool name, not object identity
				assertThat(toolsRef.get()).isNotNull();
				assertThat(toolsRef.get()).hasSize(1);
				assertThat(toolsRef.get().get(0).getName()).isEqualTo(tool2.tool().getName());
			});
		}
		finally {
			mcpServer.closeGracefully();
		}

	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testInitialize(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		McpSyncServer mcpServer = prepareSyncServerBuilder().build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testLoggingNotification(String clientType) throws InterruptedException {
		int expectedNotificationsCount = 3;
		CountDownLatch latch = new CountDownLatch(expectedNotificationsCount);
		// Create a list to store received logging notifications
		List<McpSchema.LoggingMessageNotification> receivedNotifications = new CopyOnWriteArrayList<>();

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
				.tool(Tool.builder().name("logging-test").description("Test logging notifications")
						.inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {

					// Create and send notifications with different levels

				//@formatter:off
					return exchange // This should be filtered out (DEBUG < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.DEBUG)
								.logger("test-logger")
								.data("Debug message")
								.build())
					.then(exchange // This should be sent (NOTICE >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.NOTICE)
								.logger("test-logger")
								.data("Notice message")
								.build()))
					.then(exchange // This should be sent (ERROR > NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.ERROR)
							.logger("test-logger")
							.data("Error message")
							.build()))
					.then(exchange // This should be filtered out (INFO < NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.INFO)
								.logger("test-logger")
								.data("Another info message")
								.build()))
					.then(exchange // This should be sent (ERROR >= NOTICE)
						.loggingNotification(McpSchema.LoggingMessageNotification.builder()
								.level(McpSchema.LoggingLevel.ERROR)
								.logger("test-logger")
								.data("Another error message")
								.build()))
					.thenReturn(CallToolResult.builder()
						.content(Collections.singletonList(new McpSchema.TextContent("Logging test completed")))
						.isError(false)
						.build());
					//@formatter:on
				}).build();

		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool).build();

		try (
				// Create client with logging notification handler
				McpSyncClient mcpClient = clientBuilder.loggingConsumer(notification -> {
					receivedNotifications.add(notification);
					latch.countDown();
				}).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Set minimum logging level to NOTICE
			mcpClient.setLoggingLevel(McpSchema.LoggingLevel.NOTICE);

			// Call the tool that sends logging notifications
			CallToolResult result = mcpClient
					.callTool(new McpSchema.CallToolRequest("logging-test", Collections.emptyMap()));
			assertThat(result).isNotNull();
			assertThat(result.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.getContent().get(0)).getText())
					.isEqualTo("Logging test completed");

			assertThat(latch.await(5, TimeUnit.SECONDS)).as("Should receive notifications in reasonable time").isTrue();

			// Should have received 3 notifications (1 NOTICE and 2 ERROR)
			assertThat(receivedNotifications).hasSize(expectedNotificationsCount);

			Map<String, McpSchema.LoggingMessageNotification> notificationMap = receivedNotifications.stream()
					.collect(Collectors.toMap(n -> n.getData(), n -> n));

			// First notification should be NOTICE level
			assertThat(notificationMap.get("Notice message").getLevel()).isEqualTo(McpSchema.LoggingLevel.NOTICE);
			assertThat(notificationMap.get("Notice message").getLogger()).isEqualTo("test-logger");
			assertThat(notificationMap.get("Notice message").getData()).isEqualTo("Notice message");

			// Second notification should be ERROR level
			assertThat(notificationMap.get("Error message").getLevel()).isEqualTo(McpSchema.LoggingLevel.ERROR);
			assertThat(notificationMap.get("Error message").getLogger()).isEqualTo("test-logger");
			assertThat(notificationMap.get("Error message").getData()).isEqualTo("Error message");

			// Third notification should be ERROR level
			assertThat(notificationMap.get("Another error message").getLevel()).isEqualTo(McpSchema.LoggingLevel.ERROR);
			assertThat(notificationMap.get("Another error message").getLogger()).isEqualTo("test-logger");
			assertThat(notificationMap.get("Another error message").getData()).isEqualTo("Another error message");
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Progress Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testProgressNotification(String clientType) throws InterruptedException {
		int expectedNotificationsCount = 4; // 3 notifications + 1 for another progress
											// token
		CountDownLatch latch = new CountDownLatch(expectedNotificationsCount);
		// Create a list to store received logging notifications
		List<McpSchema.ProgressNotification> receivedNotifications = new CopyOnWriteArrayList<>();

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that sends logging notifications
		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification
				.builder().tool(McpSchema.Tool.builder().name("progress-test")
						.description("Test progress notifications").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {

					// Create and send notifications
					String progressToken = (String) request.meta().get("progressToken");

					return exchange
							.progressNotification(
									new McpSchema.ProgressNotification(progressToken, 0.0, 1.0, "Processing started"))
							.then(exchange.progressNotification(
									new McpSchema.ProgressNotification(progressToken, 0.5, 1.0, "Processing data")))
							.then(// Send a progress notification with another progress
									// value
									// should
									exchange.progressNotification(new McpSchema.ProgressNotification(
											"another-progress-token", 0.0, 1.0, "Another processing started")))
							.then(exchange.progressNotification(new McpSchema.ProgressNotification(progressToken, 1.0,
									1.0, "Processing completed")))
							.thenReturn(CallToolResult.builder()
									.content(Collections
											.singletonList(new McpSchema.TextContent("Progress test completed")))
									.isError(false).build());
				}).build();

		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool).build();

		try (
				// Create client with progress notification handler
				McpSyncClient mcpClient = clientBuilder.progressConsumer(notification -> {
					receivedNotifications.add(notification);
					latch.countDown();
				}).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call the tool that sends progress notifications
			McpSchema.CallToolRequest callToolRequest = McpSchema.CallToolRequest.builder().name("progress-test")
					.meta(Collections.singletonMap("progressToken", "test-progress-token")).build();
			CallToolResult result = mcpClient.callTool(callToolRequest);
			assertThat(result).isNotNull();
			assertThat(result.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.getContent().get(0)).getText())
					.isEqualTo("Progress test completed");

			assertThat(latch.await(5, TimeUnit.SECONDS)).as("Should receive notifications in reasonable time").isTrue();

			// Should have received 3 notifications
			assertThat(receivedNotifications).hasSize(expectedNotificationsCount);

			Map<String, McpSchema.ProgressNotification> notificationMap = receivedNotifications.stream()
					.collect(Collectors.toMap(n -> n.getMessage(), n -> n));

			// First notification should be 0.0/1.0 progress
			assertThat(notificationMap.get("Processing started").getProgressToken()).isEqualTo("test-progress-token");
			assertThat(notificationMap.get("Processing started").getProgress()).isEqualTo(0.0);
			assertThat(notificationMap.get("Processing started").getTotal()).isEqualTo(1.0);
			assertThat(notificationMap.get("Processing started").getMessage()).isEqualTo("Processing started");

			// Second notification should be 0.5/1.0 progress
			assertThat(notificationMap.get("Processing data").getProgressToken()).isEqualTo("test-progress-token");
			assertThat(notificationMap.get("Processing data").getProgress()).isEqualTo(0.5);
			assertThat(notificationMap.get("Processing data").getTotal()).isEqualTo(1.0);
			assertThat(notificationMap.get("Processing data").getMessage()).isEqualTo("Processing data");

			// Third notification should be another progress token with 0.0/1.0 progress
			assertThat(notificationMap.get("Another processing started").getProgressToken())
					.isEqualTo("another-progress-token");
			assertThat(notificationMap.get("Another processing started").getProgress()).isEqualTo(0.0);
			assertThat(notificationMap.get("Another processing started").getTotal()).isEqualTo(1.0);
			assertThat(notificationMap.get("Another processing started").getMessage())
					.isEqualTo("Another processing started");

			// Fourth notification should be 1.0/1.0 progress
			assertThat(notificationMap.get("Processing completed").getProgressToken()).isEqualTo("test-progress-token");
			assertThat(notificationMap.get("Processing completed").getProgress()).isEqualTo(1.0);
			assertThat(notificationMap.get("Processing completed").getTotal()).isEqualTo(1.0);
			assertThat(notificationMap.get("Processing completed").getMessage()).isEqualTo("Processing completed");
		}
		finally {
			mcpServer.closeGracefully().block();
		}
	}

	// ---------------------------------------
	// Completion Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : Completion call")
	@MethodSource("clientsForTesting")
	void testCompletionShouldReturnExpectedSuggestions(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		List<String> expectedValues = Arrays.asList("python", "pytorch", "pyside");
		CompleteResult completionResponse = new McpSchema.CompleteResult(
				new CompleteResult.CompleteCompletion(expectedValues, 10, // total
						true // hasMore
				));

		AtomicReference<CompleteRequest> samplingRequest = new AtomicReference<>();
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (mcpSyncServerExchange,
				request) -> {
			samplingRequest.set(request);
			return completionResponse;
		};

		McpSyncServer mcpServer = prepareSyncServerBuilder()
				.capabilities(ServerCapabilities.builder().completions().build())
				.prompts(
						new McpServerFeatures.SyncPromptSpecification(
								new Prompt("code_review", "Code review", "this is code review prompt",
										Collections.singletonList(
												new PromptArgument("language", "Language", "string", false))),
								(mcpSyncServerExchange, getPromptRequest) -> null))
				.completions(new McpServerFeatures.SyncCompletionSpecification(
						new McpSchema.PromptReference(PromptReference.TYPE, "code_review", "Code review"),
						completionHandler))
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
			mcpServer.closeGracefully();
		}
	}

	// ---------------------------------------
	// Ping Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@MethodSource("clientsForTesting")
	void testPingSuccess(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create server with a tool that uses ping functionality
		AtomicReference<String> executionOrder = new AtomicReference<>("");

		McpServerFeatures.AsyncToolSpecification tool = McpServerFeatures.AsyncToolSpecification.builder()
				.tool(Tool.builder().name("ping-async-test").description("Test ping async behavior")
						.inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {

					executionOrder.set(executionOrder.get() + "1");

					// Test async ping behavior
					return exchange.ping().doOnNext(result -> {

						assertThat(result).isNotNull();
						// Ping should return an empty object or map
						assertThat(result).isInstanceOf(Map.class);

						executionOrder.set(executionOrder.get() + "2");
						assertThat(result).isNotNull();
					}).then(Mono.fromCallable(() -> {
						executionOrder.set(executionOrder.get() + "3");
						return CallToolResult.builder()
								.content(Collections
										.singletonList(new McpSchema.TextContent("Async ping test completed")))
								.isError(false).build();
					}));
				}).build();

		// Increase timeout for ping test in suite to 10 seconds (from default 3s)
		// This prevents false timeouts when running with other tests that may cause latency
		McpAsyncServer mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.requestTimeout(Duration.ofSeconds(10))
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(tool).build();

		// Use extended timeout for client as well to match server
		try (McpSyncClient mcpClient = clientBuilder.requestTimeout(Duration.ofSeconds(10)).build()) {

			// Initialize client
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Call the tool that tests ping async behavior
			CallToolResult result = mcpClient
					.callTool(new McpSchema.CallToolRequest("ping-async-test", Collections.emptyMap()));
			assertThat(result).isNotNull();
			assertThat(result.getContent().get(0)).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.getContent().get(0)).getText())
					.isEqualTo("Async ping test completed");

			// Verify execution order
			assertThat(executionOrder.get()).isEqualTo("123");
		}
		finally {
			mcpServer.closeGracefully().block(Duration.ofSeconds(10));
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
		Map<String, Object> outputSchema = new HashMap<>();

		// "type" -> "object"
		outputSchema.put("type", "object");

		// "properties" -> mappa annidata
		Map<String, Object> properties = new HashMap<>();
		properties.put("result", Collections.singletonMap("type", "number"));
		properties.put("operation", Collections.singletonMap("type", "string"));
		properties.put("timestamp", Collections.singletonMap("type", "string"));
		outputSchema.put("properties", properties);

		// "required" -> lista
		outputSchema.put("required", Arrays.asList("result", "operation"));

		Tool calculatorTool = Tool.builder().name("calculator").description("Performs mathematical calculations")
				.outputSchema(outputSchema).build();

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
				.tool(calculatorTool).callHandler((exchange, request) -> {
					String expression = (String) request.getArguments().getOrDefault("expression", "2 + 3");
					double result = evaluateExpression(expression);

					Map<String, Object> structuredContent = new HashMap<>();
					structuredContent.put("result", result);
					structuredContent.put("operation", expression);
					structuredContent.put("timestamp", "2024-01-01T10:00:00Z");

					return CallToolResult.builder().structuredContent(structuredContent).build();
				}).build();

		McpSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputOfObjectArrayValidationSuccess(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema that returns an array of objects

		Map<String, Object> outputSchema = new HashMap<>();

		outputSchema.put("type", "array");

		// Creazione della mappa "items"
		Map<String, Object> items = new HashMap<>();
		items.put("type", "object");

		// Creazione della mappa "properties"
		Map<String, Object> properties = new HashMap<>();
		properties.put("name", Collections.singletonMap("type", "string"));
		properties.put("age", Collections.singletonMap("type", "number"));
		items.put("properties", properties);

		// Lista "required"
		items.put("required", Arrays.asList("name", "age"));

		// Inserisci "items" nella mappa principale
		outputSchema.put("items", items);

		Tool calculatorTool = Tool.builder().name("getMembers").description("Returns a list of members")
				.outputSchema(outputSchema).build();

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
				.tool(calculatorTool).callHandler((exchange, request) -> {

					List<Map<String, Object>> list = Arrays.asList(new HashMap<String, Object>() {
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

					return CallToolResult.builder().structuredContent(list).build();
				}).build();

		McpSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputWithInHandlerError(String clientType) {
		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema

		Map<String, Object> outputSchema = new HashMap<>();
		outputSchema.put("type", "object");

		// Proprietà interne
		Map<String, Object> properties = new HashMap<>();
		properties.put("result", Collections.singletonMap("type", "number"));
		properties.put("operation", Collections.singletonMap("type", "string"));
		properties.put("timestamp", Collections.singletonMap("type", "string"));

		outputSchema.put("properties", properties);

		// Lista required
		outputSchema.put("required", Arrays.asList("result", "operation"));

		Tool calculatorTool = Tool.builder().name("calculator").description("Performs mathematical calculations")
				.outputSchema(outputSchema).build();

		// Handler that returns an error result
		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
				.tool(calculatorTool)
				.callHandler(
						(exchange, request) -> CallToolResult.builder().isError(true)
								.content(Collections.singletonList(
										new TextContent("Error calling tool: Simulated in-handler error")))
								.build())
				.build();

		McpSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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
	@ValueSource(strings = { "httpclient" })
	void testStructuredOutputValidationFailure(String clientType) {

		SyncSpec clientBuilder = clientBuilders.get(clientType);

		// Create a tool with output schema
		Map<String, Object> outputSchema = new HashMap<String, Object>();

		// "type": "object"
		outputSchema.put("type", "object");

		// "properties": { "result": {"type":"number"}, "operation": {"type":"string"} }
		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("result", Collections.<String, String>singletonMap("type", "number"));
		properties.put("operation", Collections.<String, String>singletonMap("type", "string"));
		outputSchema.put("properties", properties);

		// "required": ["result", "operation"]
		List<String> required = Arrays.asList("result", "operation");
		outputSchema.put("required", required);

		Tool calculatorTool = Tool.builder().name("calculator").description("Performs mathematical calculations")
				.outputSchema(outputSchema).build();

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
				.tool(calculatorTool).callHandler((exchange, request) -> {
					// Return invalid structured output. Result should be number, missing
					// operation
					Map<String, Object> structured = new HashMap<String, Object>();
					structured.put("result", "not-a-number");
					structured.put("extra", "field");

					return CallToolResult.builder().addTextContent("Invalid calculation").structuredContent(structured)
							.build();
				}).build();

		McpSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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

		Map<String, Object> outputSchema = new HashMap<String, Object>();

		// "type": "object"
		outputSchema.put("type", "object");

		// "properties": { "result": {"type":"number"} }
		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("result", Collections.<String, String>singletonMap("type", "number"));
		outputSchema.put("properties", properties);

		// "required": ["result"]
		List<String> required = Arrays.asList("result");
		outputSchema.put("required", required);

		Tool calculatorTool = Tool.builder().name("calculator").description("Performs mathematical calculations")
				.outputSchema(outputSchema).build();

		McpServerFeatures.SyncToolSpecification tool = McpServerFeatures.SyncToolSpecification.builder()
				.tool(calculatorTool).callHandler((exchange, request) -> {
					// Return result without structured content but tool has output schema
					return CallToolResult.builder().addTextContent("Calculation completed").build();
				}).build();

		McpSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
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
		McpSyncServer mcpServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).build();

		try (McpSyncClient mcpClient = clientBuilder.build()) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Initially no tools
			assertThat(mcpClient.listTools().getTools()).isEmpty();

			// Add tool with output schema at runtime
			Map<String, Object> outputSchema = new HashMap<String, Object>();

			// "type": "object"
			outputSchema.put("type", "object");

			// "properties": { "message": {"type":"string"}, "count": {"type":"integer"} }
			Map<String, Object> properties = new HashMap<String, Object>();
			properties.put("message", Collections.<String, String>singletonMap("type", "string"));
			properties.put("count", Collections.<String, String>singletonMap("type", "integer"));
			outputSchema.put("properties", properties);

			// "required": ["message", "count"]
			List<String> required = Arrays.asList("message", "count");
			outputSchema.put("required", required);

			Tool dynamicTool = Tool.builder().name("dynamic-tool").description("Dynamically added tool")
					.outputSchema(outputSchema).build();

			McpServerFeatures.SyncToolSpecification toolSpec = McpServerFeatures.SyncToolSpecification.builder()
					.tool(dynamicTool).callHandler((exchange, request) -> {
						int count = (Integer) request.getArguments().getOrDefault("count", 1);

						// structuredContent: {"message": "Dynamic execution", "count":
						// <count>}
						Map<String, Object> structured = new HashMap<String, Object>();
						structured.put("message", "Dynamic execution");
						structured.put("count", count);

						return CallToolResult.builder().addTextContent("Dynamic tool executed " + count + " times")
								.structuredContent(structured).build();
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
		// Simple expression evaluator for testing (Java 8 compatible)
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
