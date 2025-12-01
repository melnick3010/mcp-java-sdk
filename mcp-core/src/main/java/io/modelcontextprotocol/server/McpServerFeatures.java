/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP server features specification that a particular server can choose to support.
 *
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 */
public class McpServerFeatures {

	/**
	 * Asynchronous server features specification.
	 */
	public static final class Async {

		private final McpSchema.Implementation serverInfo;

		private final McpSchema.ServerCapabilities serverCapabilities;

		private final List<McpServerFeatures.AsyncToolSpecification> tools;

		private final Map<String, AsyncResourceSpecification> resources;

		private final Map<String, McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates;

		private final Map<String, McpServerFeatures.AsyncPromptSpecification> prompts;

		private final Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions;

		private final List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers;

		private final String instructions;

		/**
		 * Create an instance and validate the arguments.
		 */
		public Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.AsyncToolSpecification> tools, Map<String, AsyncResourceSpecification> resources,
				Map<String, McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates,
				Map<String, McpServerFeatures.AsyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions,
				List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers,
				String instructions) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // logging
																					// enabled
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);
			this.tools = (tools != null) ? tools : Collections.<McpServerFeatures.AsyncToolSpecification>emptyList();
			this.resources = (resources != null) ? resources
					: Collections.<String, AsyncResourceSpecification>emptyMap();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates
					: Collections.<String, McpServerFeatures.AsyncResourceTemplateSpecification>emptyMap();
			this.prompts = (prompts != null) ? prompts
					: Collections.<String, McpServerFeatures.AsyncPromptSpecification>emptyMap();
			this.completions = (completions != null) ? completions
					: Collections.<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification>emptyMap();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers
					: Collections.<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>>emptyList();
			this.instructions = instructions;
		}

		/** Convert a synchronous specification into an asynchronous one. */
		public static Async fromSync(Sync syncSpec, boolean immediateExecution) {
			List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<McpServerFeatures.AsyncToolSpecification>();
			for (McpServerFeatures.SyncToolSpecification tool : syncSpec.tools()) {
				tools.add(AsyncToolSpecification.fromSync(tool, immediateExecution));
			}
			Map<String, AsyncResourceSpecification> resources = new HashMap<String, AsyncResourceSpecification>();
			for (Map.Entry<String, SyncResourceSpecification> e : syncSpec.resources().entrySet()) {
				resources.put(e.getKey(), AsyncResourceSpecification.fromSync(e.getValue(), immediateExecution));
			}
			Map<String, AsyncResourceTemplateSpecification> resourceTemplates = new HashMap<String, AsyncResourceTemplateSpecification>();
			for (Map.Entry<String, SyncResourceTemplateSpecification> e : syncSpec.resourceTemplates().entrySet()) {
				resourceTemplates.put(e.getKey(),
						AsyncResourceTemplateSpecification.fromSync(e.getValue(), immediateExecution));
			}
			Map<String, AsyncPromptSpecification> prompts = new HashMap<String, AsyncPromptSpecification>();
			for (Map.Entry<String, SyncPromptSpecification> e : syncSpec.prompts().entrySet()) {
				prompts.put(e.getKey(), AsyncPromptSpecification.fromSync(e.getValue(), immediateExecution));
			}
			Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new HashMap<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification>();
			for (Map.Entry<McpSchema.CompleteReference, SyncCompletionSpecification> e : syncSpec.completions()
				.entrySet()) {
				completions.put(e.getKey(), AsyncCompletionSpecification.fromSync(e.getValue(), immediateExecution));
			}
			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootChangeConsumers = new ArrayList<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>>();
			for (BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> rootChangeConsumer : syncSpec
				.rootsChangeConsumers()) {
				rootChangeConsumers.add((exchange, list) -> Mono
					.<Void>fromRunnable(() -> rootChangeConsumer.accept(new McpSyncServerExchange(exchange), list))
					.subscribeOn(Schedulers.boundedElastic()));
			}
			return new Async(syncSpec.serverInfo(), syncSpec.serverCapabilities(), tools, resources, resourceTemplates,
					prompts, completions, rootChangeConsumers, syncSpec.instructions());
		}

		// Accessors mirroring Java record-style
		public McpSchema.Implementation serverInfo() {
			return serverInfo;
		}

		public McpSchema.ServerCapabilities serverCapabilities() {
			return serverCapabilities;
		}

		public List<McpServerFeatures.AsyncToolSpecification> tools() {
			return tools;
		}

		public Map<String, AsyncResourceSpecification> resources() {
			return resources;
		}

		public Map<String, McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates() {
			return resourceTemplates;
		}

		public Map<String, McpServerFeatures.AsyncPromptSpecification> prompts() {
			return prompts;
		}

		public Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions() {
			return completions;
		}

		public List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers() {
			return rootsChangeConsumers;
		}

		public String instructions() {
			return instructions;
		}

	}

	/**
	 * Synchronous server features specification.
	 */
	public static final class Sync {

		private final McpSchema.Implementation serverInfo;

		private final McpSchema.ServerCapabilities serverCapabilities;

		private final List<McpServerFeatures.SyncToolSpecification> tools;

		private final Map<String, McpServerFeatures.SyncResourceSpecification> resources;

		private final Map<String, McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplates;

		private final Map<String, McpServerFeatures.SyncPromptSpecification> prompts;

		private final Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions;

		private final List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers;

		private final String instructions;

		/** Create an instance and validate the arguments. */
		public Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.SyncToolSpecification> tools,
				Map<String, McpServerFeatures.SyncResourceSpecification> resources,
				Map<String, McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplates,
				Map<String, McpServerFeatures.SyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions,
				List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers,
				String instructions) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // logging
																					// enabled
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);
			this.tools = (tools != null) ? tools : new ArrayList<McpServerFeatures.SyncToolSpecification>();
			this.resources = (resources != null) ? resources
					: new HashMap<String, McpServerFeatures.SyncResourceSpecification>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates
					: new HashMap<String, McpServerFeatures.SyncResourceTemplateSpecification>();
			this.prompts = (prompts != null) ? prompts
					: new HashMap<String, McpServerFeatures.SyncPromptSpecification>();
			this.completions = (completions != null) ? completions
					: new HashMap<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification>();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers
					: new ArrayList<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>>();
			this.instructions = instructions;
		}

		// Accessors mirroring Java record-style
		public McpSchema.Implementation serverInfo() {
			return serverInfo;
		}

		public McpSchema.ServerCapabilities serverCapabilities() {
			return serverCapabilities;
		}

		public List<McpServerFeatures.SyncToolSpecification> tools() {
			return tools;
		}

		public Map<String, McpServerFeatures.SyncResourceSpecification> resources() {
			return resources;
		}

		public Map<String, McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplates() {
			return resourceTemplates;
		}

		public Map<String, McpServerFeatures.SyncPromptSpecification> prompts() {
			return prompts;
		}

		public Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions() {
			return completions;
		}

		public List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers() {
			return rootsChangeConsumers;
		}

		public String instructions() {
			return instructions;
		}

	}

	/**
	 * Specification of a tool with its asynchronous handler function.
	 */
	public static final class AsyncToolSpecification {

		private final McpSchema.Tool tool;

		@Deprecated
		private final BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call;

		private final BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

		@Deprecated
		public AsyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call) {
			this(tool, call, (exchange, toolReq) -> call.apply(exchange, toolReq.getArguments()));
		}

		public AsyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call,
				BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {
			this.tool = tool;
			this.call = call;
			this.callHandler = callHandler;
		}

		public static AsyncToolSpecification fromSync(SyncToolSpecification syncToolSpec) {
			return fromSync(syncToolSpec, false);
		}

		public static AsyncToolSpecification fromSync(SyncToolSpecification syncToolSpec, boolean immediate) {
			if (syncToolSpec == null) {
				return null;
			}
			BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> deprecatedCall = (syncToolSpec
				.call() != null) ? (exchange, map) -> {
					Mono<McpSchema.CallToolResult> toolResult = Mono
						.fromCallable(() -> syncToolSpec.call().apply(new McpSyncServerExchange(exchange), map));
					return immediate ? toolResult : toolResult.subscribeOn(Schedulers.boundedElastic());
				} : null;
			BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler = (
					exchange, req) -> {
				Mono<McpSchema.CallToolResult> toolResult = Mono
					.fromCallable(() -> syncToolSpec.callHandler().apply(new McpSyncServerExchange(exchange), req));
				return immediate ? toolResult : toolResult.subscribeOn(Schedulers.boundedElastic());
			};
			return new AsyncToolSpecification(syncToolSpec.tool(), deprecatedCall, callHandler);
		}

		// Builder
		public static class Builder {

			private McpSchema.Tool tool;

			private BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

			public Builder tool(McpSchema.Tool tool) {
				this.tool = tool;
				return this;
			}

			public Builder callHandler(
					BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {
				this.callHandler = callHandler;
				return this;
			}

			public AsyncToolSpecification build() {
				Assert.notNull(tool, "Tool must not be null");
				Assert.notNull(callHandler, "Call handler function must not be null");
				return new AsyncToolSpecification(tool, null, callHandler);
			}

		}

		public static Builder builder() {
			return new Builder();
		}

		// Accessors
		public McpSchema.Tool tool() {
			return tool;
		}

		@Deprecated
		public BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call() {
			return call;
		}

		public BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler() {
			return callHandler;
		}

	}

	/**
	 * Specification of a resource with its asynchronous handler function.
	 */
	public static final class AsyncResourceSpecification {

		private final McpSchema.Resource resource;

		private final BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		public AsyncResourceSpecification(McpSchema.Resource resource,
				BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}

		public static AsyncResourceSpecification fromSync(SyncResourceSpecification resource,
				boolean immediateExecution) {
			if (resource == null) {
				return null;
			}
			return new AsyncResourceSpecification(resource.resource(), (exchange, req) -> {
				Mono<McpSchema.ReadResourceResult> resourceResult = Mono
					.fromCallable(() -> resource.readHandler().apply(new McpSyncServerExchange(exchange), req));
				return immediateExecution ? resourceResult : resourceResult.subscribeOn(Schedulers.boundedElastic());
			});
		}

		public McpSchema.Resource resource() {
			return resource;
		}

		public BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() {
			return readHandler;
		}

	}

	/**
	 * Specification of a resource template with its asynchronous handler function.
	 */
	public static final class AsyncResourceTemplateSpecification {

		private final McpSchema.ResourceTemplate resourceTemplate;

		private final BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		public AsyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate,
				BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resourceTemplate = resourceTemplate;
			this.readHandler = readHandler;
		}

		public static AsyncResourceTemplateSpecification fromSync(SyncResourceTemplateSpecification resource,
				boolean immediateExecution) {
			if (resource == null) {
				return null;
			}
			return new AsyncResourceTemplateSpecification(resource.resourceTemplate(), (exchange, req) -> {
				Mono<McpSchema.ReadResourceResult> resourceResult = Mono
					.fromCallable(() -> resource.readHandler().apply(new McpSyncServerExchange(exchange), req));
				return immediateExecution ? resourceResult : resourceResult.subscribeOn(Schedulers.boundedElastic());
			});
		}

		public McpSchema.ResourceTemplate resourceTemplate() {
			return resourceTemplate;
		}

		public BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() {
			return readHandler;
		}

	}

	/**
	 * Specification of a prompt template with its asynchronous handler function.
	 */
	public static final class AsyncPromptSpecification {

		private final McpSchema.Prompt prompt;

		private final BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler;

		public AsyncPromptSpecification(McpSchema.Prompt prompt,
				BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}

		public static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt, boolean immediateExecution) {
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptSpecification(prompt.prompt(), (exchange, req) -> {
				Mono<McpSchema.GetPromptResult> promptResult = Mono
					.fromCallable(() -> prompt.promptHandler().apply(new McpSyncServerExchange(exchange), req));
				return immediateExecution ? promptResult : promptResult.subscribeOn(Schedulers.boundedElastic());
			});
		}

		public McpSchema.Prompt prompt() {
			return prompt;
		}

		public BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler() {
			return promptHandler;
		}

	}

	/**
	 * Specification of a completion handler function with asynchronous execution support.
	 */
	public static final class AsyncCompletionSpecification {

		private final McpSchema.CompleteReference referenceKey;

		private final BiFunction<McpAsyncServerExchange, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler;

		public AsyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
				BiFunction<McpAsyncServerExchange, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler) {
			this.referenceKey = referenceKey;
			this.completionHandler = completionHandler;
		}

		public static AsyncCompletionSpecification fromSync(SyncCompletionSpecification completion,
				boolean immediateExecution) {
			if (completion == null) {
				return null;
			}
			return new AsyncCompletionSpecification(completion.referenceKey(), (exchange, request) -> {
				Mono<McpSchema.CompleteResult> completionResult = Mono.fromCallable(
						() -> completion.completionHandler().apply(new McpSyncServerExchange(exchange), request));
				return immediateExecution ? completionResult
						: completionResult.subscribeOn(Schedulers.boundedElastic());
			});
		}

		public McpSchema.CompleteReference referenceKey() {
			return referenceKey;
		}

		public BiFunction<McpAsyncServerExchange, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler() {
			return completionHandler;
		}

	}

	/**
	 * Specification of a tool with its synchronous handler function.
	 */
	public static final class SyncToolSpecification {

		private final McpSchema.Tool tool;

		@Deprecated
		private final BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call;

		private final BiFunction<McpSyncServerExchange, CallToolRequest, McpSchema.CallToolResult> callHandler;

		@Deprecated
		public SyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call) {
			this(tool, call, (exchange, toolReq) -> call.apply(exchange, toolReq.getArguments()));
		}

		public SyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call,
				BiFunction<McpSyncServerExchange, CallToolRequest, McpSchema.CallToolResult> callHandler) {
			this.tool = tool;
			this.call = call;
			this.callHandler = callHandler;
		}

		// Builder for SyncToolSpecification
		public static class Builder {

			private McpSchema.Tool tool;

			private BiFunction<McpSyncServerExchange, CallToolRequest, McpSchema.CallToolResult> callHandler;

			public Builder tool(McpSchema.Tool tool) {
				this.tool = tool;
				return this;
			}

			public Builder callHandler(
					BiFunction<McpSyncServerExchange, CallToolRequest, McpSchema.CallToolResult> callHandler) {
				this.callHandler = callHandler;
				return this;
			}

			public SyncToolSpecification build() {
				Assert.notNull(tool, "Tool must not be null");
				Assert.notNull(callHandler, "CallTool function must not be null");
				return new SyncToolSpecification(tool, null, callHandler);
			}

		}

		public static Builder builder() {
			return new Builder();
		}

		// Accessors
		public McpSchema.Tool tool() {
			return tool;
		}

		@Deprecated
		public BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call() {
			return call;
		}

		public BiFunction<McpSyncServerExchange, CallToolRequest, McpSchema.CallToolResult> callHandler() {
			return callHandler;
		}

	}

	/**
	 * Specification of a resource with its synchronous handler function.
	 */
	public static final class SyncResourceSpecification {

		private final McpSchema.Resource resource;

		private final BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;

		public SyncResourceSpecification(McpSchema.Resource resource,
				BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}

		public McpSchema.Resource resource() {
			return resource;
		}

		public BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() {
			return readHandler;
		}

	}

	/**
	 * Specification of a resource template with its synchronous handler function.
	 */
	public static final class SyncResourceTemplateSpecification {

		private final McpSchema.ResourceTemplate resourceTemplate;

		private final BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;

		public SyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate,
				BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resourceTemplate = resourceTemplate;
			this.readHandler = readHandler;
		}

		public McpSchema.ResourceTemplate resourceTemplate() {
			return resourceTemplate;
		}

		public BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() {
			return readHandler;
		}

	}

	/**
	 * Specification of a prompt template with its synchronous handler function.
	 */
	public static final class SyncPromptSpecification {

		private final McpSchema.Prompt prompt;

		private final BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler;

		public SyncPromptSpecification(McpSchema.Prompt prompt,
				BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}

		public McpSchema.Prompt prompt() {
			return prompt;
		}

		public BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler() {
			return promptHandler;
		}

	}

	/**
	 * Specification of a completion handler function with synchronous execution support.
	 */
	public static final class SyncCompletionSpecification {

		private final McpSchema.CompleteReference referenceKey;

		private final BiFunction<McpSyncServerExchange, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler;

		public SyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
				BiFunction<McpSyncServerExchange, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler) {
			this.referenceKey = referenceKey;
			this.completionHandler = completionHandler;
		}

		public McpSchema.CompleteReference referenceKey() {
			return referenceKey;
		}

		public BiFunction<McpSyncServerExchange, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler() {
			return completionHandler;
		}

	}

}
