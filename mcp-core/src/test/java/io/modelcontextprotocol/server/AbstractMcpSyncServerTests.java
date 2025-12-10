/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for the {@link McpSyncServer} that can be used with different
 * {@link McpServerTransportProvider} implementations.
 *
 * @author Christian Tzolov
 */
// KEEP IN SYNC with the class in mcp-test module
public abstract class AbstractMcpSyncServerTests {

	private static final String TEST_TOOL_NAME = "test-tool";

	private static final String TEST_RESOURCE_URI = "test://resource";

	private static final String TEST_PROMPT_NAME = "test-prompt";

	abstract protected McpServer.SyncSpecification<?> prepareSyncServerBuilder();

	protected void onStart() {
	}

	protected void onClose() {
	}

	@BeforeEach
	void setUp() {
		// onStart();
	}

	@AfterEach
	void tearDown() {
		onClose();
	}

	// ---------------------------------------
	// Server Lifecycle Tests
	// ---------------------------------------

	@Test
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> McpServer.sync((McpServerTransportProvider) null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Transport provider must not be null");

		assertThatThrownBy(() -> prepareSyncServerBuilder().serverInfo(null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Server info must not be null");
	}

	@Test
	void testGracefulShutdown() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testImmediateClose() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::close).doesNotThrowAnyException();
	}

	@Test
	void testGetAsyncServer() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThat(mcpSyncServer.getAsyncServer()).isNotNull();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	@Test
	@Deprecated
	void testAddTool() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).build();

		Tool newTool = McpSchema.Tool.builder().name("new-tool").title("New test tool").inputSchema(EMPTY_JSON_SCHEMA)
				.build();
		assertThatCode(() -> mcpSyncServer.addTool(new McpServerFeatures.SyncToolSpecification(newTool,
				(exchange, args) -> CallToolResult.builder().content(Collections.emptyList()).isError(false).build())))
						.doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddToolCall() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).build();

		Tool newTool = McpSchema.Tool.builder().name("new-tool").title("New test tool").inputSchema(EMPTY_JSON_SCHEMA)
				.build();

		assertThatCode(
				() -> mcpSyncServer
						.addTool(McpServerFeatures.SyncToolSpecification.builder().tool(newTool)
								.callHandler((exchange, request) -> CallToolResult.builder()
										.content(Collections.emptyList()).isError(false).build())
								.build())).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	@Deprecated
	void testAddDuplicateTool() {
		Tool duplicateTool = McpSchema.Tool.builder().name(TEST_TOOL_NAME).title("Duplicate tool")
				.inputSchema(EMPTY_JSON_SCHEMA).build();

		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tool(duplicateTool, (exchange,
						args) -> CallToolResult.builder().content(Collections.emptyList()).isError(false).build())
				.build();

		assertThatCode(() -> mcpSyncServer.addTool(new McpServerFeatures.SyncToolSpecification(duplicateTool,
				(exchange, args) -> CallToolResult.builder().content(Collections.emptyList()).isError(false).build())))
						.doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddDuplicateToolCall() {
		Tool duplicateTool = McpSchema.Tool.builder().name(TEST_TOOL_NAME).title("Duplicate tool")
				.inputSchema(EMPTY_JSON_SCHEMA).build();

		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).toolCall(duplicateTool, (exchange,
						request) -> CallToolResult.builder().content(Collections.emptyList()).isError(false).build())
				.build();

		assertThatCode(
				() -> mcpSyncServer
						.addTool(McpServerFeatures.SyncToolSpecification.builder().tool(duplicateTool)
								.callHandler((exchange, request) -> CallToolResult.builder()
										.content(Collections.emptyList()).isError(false).build())
								.build())).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testDuplicateToolCallDuringBuilding() {
		Tool duplicateTool = McpSchema.Tool.builder().name("duplicate-build-toolcall")
				.title("Duplicate toolcall during building").inputSchema(EMPTY_JSON_SCHEMA).build();

		assertThatThrownBy(() -> prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build())
				.toolCall(duplicateTool,
						(exchange, request) -> CallToolResult.builder().content(Collections.emptyList()).isError(false)
								.build())
				.toolCall(duplicateTool,
						(exchange, request) -> CallToolResult.builder().content(Collections.emptyList()).isError(false)
								.build()) // Duplicate!
				.build()).isInstanceOf(IllegalArgumentException.class)
						.hasMessage("Tool with name 'duplicate-build-toolcall' is already registered.");
	}

	@Test
	void testDuplicateToolsInBatchListRegistration() {
		Tool duplicateTool = McpSchema.Tool.builder().name("batch-list-tool").title("Duplicate tool in batch list")
				.inputSchema(EMPTY_JSON_SCHEMA).build();
		List<McpServerFeatures.SyncToolSpecification> specs = Arrays.asList(McpServerFeatures.SyncToolSpecification
				.builder().tool(duplicateTool)
				.callHandler((exchange, request) -> CallToolResult.builder().content(Collections.emptyList())
						.isError(false).build())
				.build(),
				McpServerFeatures.SyncToolSpecification.builder().tool(duplicateTool).callHandler((exchange,
						request) -> CallToolResult.builder().content(Collections.emptyList()).isError(false).build())
						.build() // Duplicate!
		);

		assertThatThrownBy(() -> prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).tools(specs).build())
						.isInstanceOf(IllegalArgumentException.class)
						.hasMessage("Tool with name 'batch-list-tool' is already registered.");
	}

	@Test
	void testDuplicateToolsInBatchVarargsRegistration() {
		Tool duplicateTool = McpSchema.Tool.builder().name("batch-varargs-tool")
				.title("Duplicate tool in batch varargs").inputSchema(EMPTY_JSON_SCHEMA).build();

		assertThatThrownBy(
				() -> prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
						.capabilities(ServerCapabilities.builder().tools(true).build()).tools(
								McpServerFeatures.SyncToolSpecification.builder().tool(duplicateTool)
										.callHandler((exchange, request) -> CallToolResult.builder()
												.content(Collections.emptyList()).isError(false).build())
										.build(),
								McpServerFeatures.SyncToolSpecification
										.builder().tool(duplicateTool).callHandler((exchange, request) -> CallToolResult
												.builder().content(Collections.emptyList()).isError(false).build())
										.build() // Duplicate!
						).build()).isInstanceOf(IllegalArgumentException.class)
								.hasMessage("Tool with name 'batch-varargs-tool' is already registered.");
	}

	@Test
	void testRemoveTool() {
		Tool tool = McpSchema.Tool.builder().name(TEST_TOOL_NAME).title("Test tool").inputSchema(EMPTY_JSON_SCHEMA)
				.build();

		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).toolCall(tool, (exchange,
						args) -> CallToolResult.builder().content(Collections.emptyList()).isError(false).build())
				.build();

		assertThatCode(() -> mcpSyncServer.removeTool(TEST_TOOL_NAME)).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentTool() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().tools(true).build()).build();

		assertThatCode(() -> mcpSyncServer.removeTool("nonexistent-tool")).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testNotifyToolsListChanged() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::notifyToolsListChanged).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resources Tests
	// ---------------------------------------

	@Test
	void testNotifyResourcesListChanged() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::notifyResourcesListChanged).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testNotifyResourcesUpdated() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(() -> mcpSyncServer
				.notifyResourcesUpdated(new McpSchema.ResourcesUpdatedNotification(TEST_RESOURCE_URI)))
						.doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddResource() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build()).build();

		Resource resource = Resource.builder().uri(TEST_RESOURCE_URI).name("Test Resource").mimeType("text/plain")
				.description("Test resource description").build();
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(Collections.emptyList()));

		assertThatCode(() -> mcpSyncServer.addResource(specification)).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithNullSpecification() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build()).build();

		assertThatThrownBy(() -> mcpSyncServer.addResource((McpServerFeatures.SyncResourceSpecification) null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Resource must not be null");

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceWithoutCapability() {
		McpSyncServer serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		Resource resource = Resource.builder().uri(TEST_RESOURCE_URI).name("Test Resource").mimeType("text/plain")
				.description("Test resource description").build();
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(Collections.emptyList()));

		assertThatThrownBy(() -> serverWithoutResources.addResource(specification))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Server must be configured with resource capabilities");
	}

	@Test
	void testRemoveResourceWithoutCapability() {
		McpSyncServer serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutResources.removeResource(TEST_RESOURCE_URI))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Server must be configured with resource capabilities");
	}

	@Test
	void testListResources() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build()).build();

		Resource resource = Resource.builder().uri(TEST_RESOURCE_URI).name("Test Resource").mimeType("text/plain")
				.description("Test resource description").build();
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(Collections.emptyList()));

		mcpSyncServer.addResource(specification);
		List<McpSchema.Resource> resources = mcpSyncServer.listResources();

		assertThat(resources).hasSize(1);
		assertThat(resources.get(0).uri()).isEqualTo(TEST_RESOURCE_URI);

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveResource() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build()).build();

		Resource resource = Resource.builder().uri(TEST_RESOURCE_URI).name("Test Resource").mimeType("text/plain")
				.description("Test resource description").build();
		McpServerFeatures.SyncResourceSpecification specification = new McpServerFeatures.SyncResourceSpecification(
				resource, (exchange, req) -> new ReadResourceResult(Collections.emptyList()));

		mcpSyncServer.addResource(specification);
		assertThatCode(() -> mcpSyncServer.removeResource(TEST_RESOURCE_URI)).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentResource() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build()).build();

		// Removing a non-existent resource should complete successfully (no error)
		// as per the new implementation that just logs a warning
		assertThatCode(() -> mcpSyncServer.removeResource("nonexistent://resource")).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Resource Template Tests
	// ---------------------------------------

	@Test
	void testAddResourceTemplate() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build()).build();

		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder().uriTemplate("test://template/{id}")
				.name("test-template").description("Test resource template").mimeType("text/plain").build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(Collections.emptyList()));

		assertThatCode(() -> mcpSyncServer.addResourceTemplate(specification)).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddResourceTemplateWithoutCapability() {
		// Create a server without resource capabilities
		McpSyncServer serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder().uriTemplate("test://template/{id}")
				.name("test-template").description("Test resource template").mimeType("text/plain").build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(Collections.emptyList()));

		assertThatThrownBy(() -> serverWithoutResources.addResourceTemplate(specification))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Server must be configured with resource capabilities");
	}

	@Test
	void testRemoveResourceTemplate() {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder().uriTemplate("test://template/{id}")
				.name("test-template").description("Test resource template").mimeType("text/plain").build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(Collections.emptyList()));

		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build())
				.resourceTemplates(specification).build();

		assertThatCode(() -> mcpSyncServer.removeResourceTemplate("test://template/{id}")).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveResourceTemplateWithoutCapability() {
		// Create a server without resource capabilities
		McpSyncServer serverWithoutResources = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutResources.removeResourceTemplate("test://template/{id}"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Server must be configured with resource capabilities");
	}

	@Test
	void testRemoveNonexistentResourceTemplate() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build()).build();

		assertThatCode(() -> mcpSyncServer.removeResourceTemplate("nonexistent://template/{id}"))
				.doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testListResourceTemplates() {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder().uriTemplate("test://template/{id}")
				.name("test-template").description("Test resource template").mimeType("text/plain").build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(Collections.emptyList()));

		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build())
				.resourceTemplates(specification).build();

		List<McpSchema.ResourceTemplate> templates = mcpSyncServer.listResourceTemplates();

		assertThat(templates).isNotNull();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Prompts Tests
	// ---------------------------------------

	@Test
	void testNotifyPromptsListChanged() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatCode(mcpSyncServer::notifyPromptsListChanged).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testAddPromptWithNullSpecification() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().prompts(false).build()).build();

		assertThatThrownBy(() -> mcpSyncServer.addPrompt((McpServerFeatures.SyncPromptSpecification) null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Prompt specification must not be null");
	}

	@Test
	void testAddPromptWithoutCapability() {
		McpSyncServer serverWithoutPrompts = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", "Test Prompt", Collections.emptyList());

		McpServerFeatures.SyncPromptSpecification specification = new McpServerFeatures.SyncPromptSpecification(prompt,
				(exchange, req) -> new GetPromptResult("Test prompt description", Arrays.asList(
						new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content")))));

		assertThatThrownBy(() -> serverWithoutPrompts.addPrompt(specification))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Server must be configured with prompt capabilities");
	}

	@Test
	void testRemovePromptWithoutCapability() {
		McpSyncServer serverWithoutPrompts = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThatThrownBy(() -> serverWithoutPrompts.removePrompt(TEST_PROMPT_NAME))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Server must be configured with prompt capabilities");
	}

	@Test
	void testRemovePrompt() {
		Prompt prompt = new Prompt(TEST_PROMPT_NAME, "Test Prompt", "Test Prompt", Collections.emptyList());
		McpServerFeatures.SyncPromptSpecification specification = new McpServerFeatures.SyncPromptSpecification(prompt,
				(exchange, req) -> new GetPromptResult("Test prompt description", Arrays.asList(
						new PromptMessage(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("Test content")))));

		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().prompts(true).build()).prompts(specification).build();

		assertThatCode(() -> mcpSyncServer.removePrompt(TEST_PROMPT_NAME)).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentPrompt() {
		McpSyncServer mcpSyncServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().prompts(true).build()).build();

		assertThatCode(() -> mcpSyncServer.removePrompt("nonexistent://template/{id}")).doesNotThrowAnyException();

		assertThatCode(mcpSyncServer::closeGracefully).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------

	@Test
	void testRootsChangeHandlers() {
		// Test with single consumer
		Root[] rootsReceived = new McpSchema.Root[1];
		boolean[] consumerCalled = new boolean[1];

		McpSyncServer singleConsumerServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.rootsChangeHandlers(Arrays.asList((exchange, roots) -> {
					consumerCalled[0] = true;
					if (!roots.isEmpty()) {
						rootsReceived[0] = roots.get(0);
					}
				})).build();
		assertThat(singleConsumerServer).isNotNull();
		assertThatCode(singleConsumerServer::closeGracefully).doesNotThrowAnyException();
		onClose();

		// Test with multiple consumers
		boolean[] consumer1Called = new boolean[1];
		boolean[] consumer2Called = new boolean[1];
		List[] rootsContent = new List[1];

		McpSyncServer multipleConsumersServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.rootsChangeHandlers(Arrays.asList((exchange, roots) -> {
					consumer1Called[0] = true;
					rootsContent[0] = roots;
				}, (exchange, roots) -> consumer2Called[0] = true)).build();

		assertThat(multipleConsumersServer).isNotNull();
		assertThatCode(multipleConsumersServer::closeGracefully).doesNotThrowAnyException();
		onClose();

		// Test error handling
		McpSyncServer errorHandlingServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0")
				.rootsChangeHandlers(Arrays.asList((exchange, roots) -> {
					throw new RuntimeException("Test error");
				})).build();

		assertThat(errorHandlingServer).isNotNull();
		assertThatCode(errorHandlingServer::closeGracefully).doesNotThrowAnyException();
		onClose();

		// Test without consumers
		McpSyncServer noConsumersServer = prepareSyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		assertThat(noConsumersServer).isNotNull();
		assertThatCode(noConsumersServer::closeGracefully).doesNotThrowAnyException();
	}

}
