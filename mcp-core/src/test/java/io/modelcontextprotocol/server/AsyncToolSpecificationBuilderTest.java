/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Tests for {@link McpServerFeatures.AsyncToolSpecification.Builder}.
 *
 * @author Christian Tzolov
 */
class AsyncToolSpecificationBuilderTest {

	@Test
	void builderShouldCreateValidAsyncToolSpecification() {

		Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.title("A test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		McpServerFeatures.AsyncToolSpecification specification = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange,
					request) -> Mono.just(CallToolResult.builder()
						.content(Collections.singletonList(new TextContent("Test result")))
						.isError(false)
						.build()))
			.build();

		assertThat(specification).isNotNull();
		assertThat(specification.tool()).isEqualTo(tool);
		assertThat(specification.callHandler()).isNotNull();
		assertThat(specification.call()).isNull(); // deprecated field should be null
	}

	@Test
	void builderShouldThrowExceptionWhenToolIsNull() {
		assertThatThrownBy(() -> McpServerFeatures.AsyncToolSpecification.builder()
			.callHandler((exchange, request) -> Mono
				.just(CallToolResult.builder().content(Collections.emptyList()).isError(false).build()))
			.build()).isInstanceOf(IllegalArgumentException.class).hasMessage("Tool must not be null");
	}

	@Test
	void builderShouldThrowExceptionWhenCallToolIsNull() {
		Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.title("A test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();

		assertThatThrownBy(() -> McpServerFeatures.AsyncToolSpecification.builder().tool(tool).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Call handler function must not be null");
	}

	@Test
	void builderShouldAllowMethodChaining() {
		Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.title("A test tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		McpServerFeatures.AsyncToolSpecification.Builder builder = McpServerFeatures.AsyncToolSpecification.builder();

		// Then - verify method chaining returns the same builder instance
		assertThat(builder.tool(tool)).isSameAs(builder);
		assertThat(builder.callHandler((exchange, request) -> Mono
			.just(CallToolResult.builder().content(Collections.emptyList()).isError(false).build()))).isSameAs(builder);
	}

	@Test
	void builtSpecificationShouldExecuteCallToolCorrectly() {
		Tool tool = McpSchema.Tool.builder()
			.name("calculator")
			.title("Simple calculator")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		String expectedResult = "42";

		McpServerFeatures.AsyncToolSpecification specification = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> {
				return Mono.just(CallToolResult.builder()
					.content(Collections.singletonList(new TextContent(expectedResult)))
					.isError(false)
					.build());
			})
			.build();

		CallToolRequest request = new CallToolRequest("calculator", Collections.emptyMap());
		Mono<CallToolResult> resultMono = specification.callHandler().apply(null, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.getContent()).hasSize(1);
			assertThat(result.getContent().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.getContent().get(0)).getText()).isEqualTo(expectedResult);
			assertThat(result.getIsError()).isFalse();
		}).verifyComplete();
	}

	@Test
	@SuppressWarnings("deprecation")
	void deprecatedConstructorShouldWorkCorrectly() {
		Tool tool = McpSchema.Tool.builder()
			.name("deprecated-tool")
			.title("A deprecated tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		String expectedResult = "deprecated result";

		// Test the deprecated constructor that takes a 'call' function
		McpServerFeatures.AsyncToolSpecification specification = new McpServerFeatures.AsyncToolSpecification(tool,
				(exchange,
						arguments) -> Mono.just(CallToolResult.builder()
							.content(Collections.singletonList(new TextContent(expectedResult)))
							.isError(false)
							.build()));

		assertThat(specification).isNotNull();
		assertThat(specification.tool()).isEqualTo(tool);
		assertThat(specification.call()).isNotNull(); // deprecated field should be set
		assertThat(specification.callHandler()).isNotNull(); // should be automatically
																// created

		// Test that the callTool function works (it should delegate to the call function)
		CallToolRequest request = new CallToolRequest("deprecated-tool", Collections.singletonMap("arg1", "value1"));
		Mono<CallToolResult> resultMono = specification.callHandler().apply(null, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.getContent()).hasSize(1);
			assertThat(result.getContent().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.getContent().get(0)).getText()).isEqualTo(expectedResult);
			assertThat(result.getIsError()).isFalse();
		}).verifyComplete();

		// Test that the deprecated call function also works directly
		Mono<CallToolResult> callResultMono = specification.call().apply(null, request.getArguments());

		StepVerifier.create(callResultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.getContent()).hasSize(1);
			assertThat(result.getContent().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.getContent().get(0)).getText()).isEqualTo(expectedResult);
			assertThat(result.getIsError()).isFalse();
		}).verifyComplete();
	}

	@Test
	void fromSyncShouldConvertSyncToolSpecificationCorrectly() {
		Tool tool = McpSchema.Tool.builder()
			.name("sync-tool")
			.title("A sync tool")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		String expectedResult = "sync result";

		// Create a sync tool specification
		McpServerFeatures.SyncToolSpecification syncSpec = McpServerFeatures.SyncToolSpecification.builder()
			.tool(tool)
			.callHandler((exchange, request) -> CallToolResult.builder()
				.content(Collections.singletonList(new TextContent(expectedResult)))
				.isError(false)
				.build())
			.build();

		// Convert to async using fromSync
		McpServerFeatures.AsyncToolSpecification asyncSpec = McpServerFeatures.AsyncToolSpecification
			.fromSync(syncSpec);

		assertThat(asyncSpec).isNotNull();
		assertThat(asyncSpec.tool()).isEqualTo(tool);
		assertThat(asyncSpec.callHandler()).isNotNull();
		assertThat(asyncSpec.call()).isNull(); // should be null since sync spec doesn't
												// have deprecated call

		// Test that the converted async specification works correctly
		CallToolRequest request = new CallToolRequest("sync-tool", Collections.singletonMap("param", "value"));
		Mono<CallToolResult> resultMono = asyncSpec.callHandler().apply(null, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.getContent()).hasSize(1);
			assertThat(result.getContent().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.getContent().get(0)).getText()).isEqualTo(expectedResult);
			assertThat(result.getIsError()).isFalse();
		}).verifyComplete();
	}

	@Test
	@SuppressWarnings("deprecation")
	void fromSyncShouldConvertSyncToolSpecificationWithDeprecatedCallCorrectly() {
		Tool tool = McpSchema.Tool.builder()
			.name("sync-deprecated-tool")
			.title("A sync tool with deprecated call")
			.inputSchema(EMPTY_JSON_SCHEMA)
			.build();
		String expectedResult = "sync deprecated result";
		McpAsyncServerExchange nullExchange = null; // Mock or create a suitable exchange
													// if needed

		// Create a sync tool specification using the deprecated constructor
		McpServerFeatures.SyncToolSpecification syncSpec = new McpServerFeatures.SyncToolSpecification(tool,
				(exchange, arguments) -> CallToolResult.builder()
					.content(Collections.singletonList(new TextContent(expectedResult)))
					.isError(false)
					.build());

		// Convert to async using fromSync
		McpServerFeatures.AsyncToolSpecification asyncSpec = McpServerFeatures.AsyncToolSpecification
			.fromSync(syncSpec);

		assertThat(asyncSpec).isNotNull();
		assertThat(asyncSpec.tool()).isEqualTo(tool);
		assertThat(asyncSpec.callHandler()).isNotNull();
		assertThat(asyncSpec.call()).isNotNull(); // should be set since sync spec has
													// deprecated call

		// Test that the converted async specification works correctly via callTool
		CallToolRequest request = new CallToolRequest("sync-deprecated-tool",
				Collections.singletonMap("param", "value"));
		Mono<CallToolResult> resultMono = asyncSpec.callHandler().apply(nullExchange, request);

		StepVerifier.create(resultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.getContent()).hasSize(1);
			assertThat(result.getContent().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.getContent().get(0)).getText()).isEqualTo(expectedResult);
			assertThat(result.getIsError()).isFalse();
		}).verifyComplete();

		// Test that the deprecated call function also works
		Mono<CallToolResult> callResultMono = asyncSpec.call().apply(nullExchange, request.getArguments());

		StepVerifier.create(callResultMono).assertNext(result -> {
			assertThat(result).isNotNull();
			assertThat(result.getContent()).hasSize(1);
			assertThat(result.getContent().get(0)).isInstanceOf(TextContent.class);
			assertThat(((TextContent) result.getContent().get(0)).getText()).isEqualTo(expectedResult);
			assertThat(result.getIsError()).isFalse();
		}).verifyComplete();
	}

	@Test
	void fromSyncShouldReturnNullWhenSyncSpecIsNull() {
		assertThat(McpServerFeatures.AsyncToolSpecification.fromSync(null)).isNull();
	}

}
