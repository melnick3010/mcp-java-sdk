/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP stateless server features specification that a particular server can choose to
 * support.
 */
public class McpStatelessServerFeatures {

	/** Asynchronous server features specification (Java 8 replacement for record). */
	public static final class Async {

		private final McpSchema.Implementation serverInfo;

		private final McpSchema.ServerCapabilities serverCapabilities;

		private final List<McpStatelessServerFeatures.AsyncToolSpecification> tools;

		private final Map<String, AsyncResourceSpecification> resources;

		private final Map<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates;

		private final Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts;

		private final Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions;

		private final String instructions;

		public Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpStatelessServerFeatures.AsyncToolSpecification> tools,
				Map<String, AsyncResourceSpecification> resources,
				Map<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates,
				Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions,
				String instructions) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
							null, // stateless server: logging capability not exposed
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);
			this.tools = (tools != null) ? tools
					: Collections.<McpStatelessServerFeatures.AsyncToolSpecification>emptyList();
			this.resources = (resources != null) ? resources
					: Collections.<String, AsyncResourceSpecification>emptyMap();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates
					: Collections.<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification>emptyMap();
			this.prompts = (prompts != null) ? prompts
					: Collections.<String, McpStatelessServerFeatures.AsyncPromptSpecification>emptyMap();
			this.completions = (completions != null) ? completions
					: Collections.<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification>emptyMap();
			this.instructions = instructions;
		}

		/**
		 * Convert a synchronous specification into an asynchronous one with optional
		 * offload.
		 */
		public static Async fromSync(Sync syncSpec, boolean immediateExecution) {
			List<McpStatelessServerFeatures.AsyncToolSpecification> tools = new ArrayList<McpStatelessServerFeatures.AsyncToolSpecification>();
			for (McpStatelessServerFeatures.SyncToolSpecification tool : syncSpec.tools()) {
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
			Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions = new HashMap<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification>();
			for (Map.Entry<McpSchema.CompleteReference, SyncCompletionSpecification> e : syncSpec.completions()
				.entrySet()) {
				completions.put(e.getKey(), AsyncCompletionSpecification.fromSync(e.getValue(), immediateExecution));
			}
			return new Async(syncSpec.serverInfo(), syncSpec.serverCapabilities(), tools, resources, resourceTemplates,
					prompts, completions, syncSpec.instructions());
		}

		// Accessors to mirror record API
		public McpSchema.Implementation serverInfo() {
			return serverInfo;
		}

		public McpSchema.ServerCapabilities serverCapabilities() {
			return serverCapabilities;
		}

		public List<McpStatelessServerFeatures.AsyncToolSpecification> tools() {
			return tools;
		}

		public Map<String, AsyncResourceSpecification> resources() {
			return resources;
		}

		public Map<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates() {
			return resourceTemplates;
		}

		public Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts() {
			return prompts;
		}

		public Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions() {
			return completions;
		}

		public String instructions() {
			return instructions;
		}

	}

	/** Synchronous server features specification (Java 8 replacement for record). */
	public static final class Sync {

		private final McpSchema.Implementation serverInfo;

		private final McpSchema.ServerCapabilities serverCapabilities;

		private final List<McpStatelessServerFeatures.SyncToolSpecification> tools;

		private final Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources;

		private final Map<String, McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates;

		private final Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts;

		private final Map<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification> completions;

		private final String instructions;

		public Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpStatelessServerFeatures.SyncToolSpecification> tools,
				Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources,
				Map<String, McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates,
				Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification> completions,
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
			this.tools = (tools != null) ? tools : new ArrayList<McpStatelessServerFeatures.SyncToolSpecification>();
			this.resources = (resources != null) ? resources
					: new HashMap<String, McpStatelessServerFeatures.SyncResourceSpecification>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates
					: new HashMap<String, McpStatelessServerFeatures.SyncResourceTemplateSpecification>();
			this.prompts = (prompts != null) ? prompts
					: new HashMap<String, McpStatelessServerFeatures.SyncPromptSpecification>();
			this.completions = (completions != null) ? completions
					: new HashMap<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification>();
			this.instructions = instructions;
		}

		// Accessors to mirror record API
		public McpSchema.Implementation serverInfo() {
			return serverInfo;
		}

		public McpSchema.ServerCapabilities serverCapabilities() {
			return serverCapabilities;
		}

		public List<McpStatelessServerFeatures.SyncToolSpecification> tools() {
			return tools;
		}

		public Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources() {
			return resources;
		}

		public Map<String, McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates() {
			return resourceTemplates;
		}

		public Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts() {
			return prompts;
		}

		public Map<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification> completions() {
			return completions;
		}

		public String instructions() {
			return instructions;
		}

	}

	/** Tool specification with asynchronous handler. */
	public static final class AsyncToolSpecification {

		private final McpSchema.Tool tool;

		private final BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

		public AsyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {
			this.tool = tool;
			this.callHandler = callHandler;
		}

		public static AsyncToolSpecification fromSync(SyncToolSpecification syncToolSpec) {
			return fromSync(syncToolSpec, false);
		}

		public static AsyncToolSpecification fromSync(SyncToolSpecification syncToolSpec, boolean immediate) {
			if (syncToolSpec == null) {
				return null;
			}
			BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> ch = new BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>>() {
				@Override
				public Mono<McpSchema.CallToolResult> apply(McpTransportContext ctx, CallToolRequest req) {
					Mono<McpSchema.CallToolResult> toolResult = Mono
						.fromCallable(new java.util.concurrent.Callable<McpSchema.CallToolResult>() {
							@Override
							public McpSchema.CallToolResult call() {
								return syncToolSpec.callHandler().apply(ctx, req);
							}
						});
					return immediate ? toolResult : toolResult.subscribeOn(Schedulers.boundedElastic());
				}
			};
			return new AsyncToolSpecification(syncToolSpec.tool(), ch);
		}

		// Builder
		public static class Builder {

			private McpSchema.Tool tool;

			private BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

			public Builder tool(McpSchema.Tool tool) {
				this.tool = tool;
				return this;
			}

			public Builder callHandler(
					BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {
				this.callHandler = callHandler;
				return this;
			}

			public AsyncToolSpecification build() {
				Assert.notNull(tool, "Tool must not be null");
				Assert.notNull(callHandler, "Call handler function must not be null");
				return new AsyncToolSpecification(tool, callHandler);
			}

		}

		public static Builder builder() {
			return new Builder();
		}

		public McpSchema.Tool tool() {
			return tool;
		}

		public BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler() {
			return callHandler;
		}

	}

	/** Resource specification with asynchronous handler. */
	public static final class AsyncResourceSpecification {

		private final McpSchema.Resource resource;

		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		public AsyncResourceSpecification(McpSchema.Resource resource,
				BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}

		public static AsyncResourceSpecification fromSync(SyncResourceSpecification resource,
				boolean immediateExecution) {
			if (resource == null) {
				return null;
			}
			return new AsyncResourceSpecification(resource.resource(),
					new BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>>() {
						@Override
						public Mono<McpSchema.ReadResourceResult> apply(McpTransportContext ctx,
								McpSchema.ReadResourceRequest req) {
							Mono<McpSchema.ReadResourceResult> resourceResult = Mono
								.fromCallable(new java.util.concurrent.Callable<McpSchema.ReadResourceResult>() {
									@Override
									public McpSchema.ReadResourceResult call() {
										return resource.readHandler().apply(ctx, req);
									}
								});
							return immediateExecution ? resourceResult
									: resourceResult.subscribeOn(Schedulers.boundedElastic());
						}
					});
		}

		public McpSchema.Resource resource() {
			return resource;
		}

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() {
			return readHandler;
		}

	}

	/** Resource template specification with asynchronous handler. */
	public static final class AsyncResourceTemplateSpecification {

		private final McpSchema.ResourceTemplate resourceTemplate;

		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler;

		public AsyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate,
				BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {
			this.resourceTemplate = resourceTemplate;
			this.readHandler = readHandler;
		}

		public static AsyncResourceTemplateSpecification fromSync(SyncResourceTemplateSpecification resource,
				boolean immediateExecution) {
			if (resource == null) {
				return null;
			}
			return new AsyncResourceTemplateSpecification(resource.resourceTemplate(),
					new BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>>() {
						@Override
						public Mono<McpSchema.ReadResourceResult> apply(McpTransportContext ctx,
								McpSchema.ReadResourceRequest req) {
							Mono<McpSchema.ReadResourceResult> resourceResult = Mono
								.fromCallable(new java.util.concurrent.Callable<McpSchema.ReadResourceResult>() {
									@Override
									public McpSchema.ReadResourceResult call() {
										return resource.readHandler().apply(ctx, req);
									}
								});
							return immediateExecution ? resourceResult
									: resourceResult.subscribeOn(Schedulers.boundedElastic());
						}
					});
		}

		public McpSchema.ResourceTemplate resourceTemplate() {
			return resourceTemplate;
		}

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler() {
			return readHandler;
		}

	}

	/** Prompt specification with asynchronous handler. */
	public static final class AsyncPromptSpecification {

		private final McpSchema.Prompt prompt;

		private final BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler;

		public AsyncPromptSpecification(McpSchema.Prompt prompt,
				BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}

		public static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt, boolean immediateExecution) {
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptSpecification(prompt.prompt(),
					new BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>>() {
						@Override
						public Mono<McpSchema.GetPromptResult> apply(McpTransportContext ctx,
								McpSchema.GetPromptRequest req) {
							Mono<McpSchema.GetPromptResult> promptResult = Mono
								.fromCallable(new java.util.concurrent.Callable<McpSchema.GetPromptResult>() {
									@Override
									public McpSchema.GetPromptResult call() {
										return prompt.promptHandler().apply(ctx, req);
									}
								});
							return immediateExecution ? promptResult
									: promptResult.subscribeOn(Schedulers.boundedElastic());
						}
					});
		}

		public McpSchema.Prompt prompt() {
			return prompt;
		}

		public BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler() {
			return promptHandler;
		}

	}

	/** Completion specification with asynchronous handler. */
	public static final class AsyncCompletionSpecification {

		private final McpSchema.CompleteReference referenceKey;

		private final BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler;

		public AsyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
				BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler) {
			this.referenceKey = referenceKey;
			this.completionHandler = completionHandler;
		}

		public static AsyncCompletionSpecification fromSync(SyncCompletionSpecification completion,
				boolean immediateExecution) {
			if (completion == null) {
				return null;
			}
			return new AsyncCompletionSpecification(completion.referenceKey(),
					new BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>>() {
						@Override
						public Mono<McpSchema.CompleteResult> apply(McpTransportContext ctx,
								McpSchema.CompleteRequest req) {
							Mono<McpSchema.CompleteResult> completionResult = Mono
								.fromCallable(new java.util.concurrent.Callable<McpSchema.CompleteResult>() {
									@Override
									public McpSchema.CompleteResult call() {
										return completion.completionHandler().apply(ctx, req);
									}
								});
							return immediateExecution ? completionResult
									: completionResult.subscribeOn(Schedulers.boundedElastic());
						}
					});
		}

		public McpSchema.CompleteReference referenceKey() {
			return referenceKey;
		}

		public BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler() {
			return completionHandler;
		}

	}

	/** Sync tool specification. */
	public static final class SyncToolSpecification {

		private final McpSchema.Tool tool;

		private final BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler;

		public SyncToolSpecification(McpSchema.Tool tool,
				BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler) {
			this.tool = tool;
			this.callHandler = callHandler;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private McpSchema.Tool tool;

			private BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler;

			public Builder tool(McpSchema.Tool tool) {
				this.tool = tool;
				return this;
			}

			public Builder callHandler(
					BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler) {
				this.callHandler = callHandler;
				return this;
			}

			public SyncToolSpecification build() {
				Assert.notNull(tool, "Tool must not be null");
				Assert.notNull(callHandler, "CallTool function must not be null");
				return new SyncToolSpecification(tool, callHandler);
			}

		}

		public McpSchema.Tool tool() {
			return tool;
		}

		public BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler() {
			return callHandler;
		}

	}

	/** Sync resource specification. */
	public static final class SyncResourceSpecification {

		private final McpSchema.Resource resource;

		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;

		public SyncResourceSpecification(McpSchema.Resource resource,
				BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resource = resource;
			this.readHandler = readHandler;
		}

		public McpSchema.Resource resource() {
			return resource;
		}

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() {
			return readHandler;
		}

	}

	/** Sync resource template specification. */
	public static final class SyncResourceTemplateSpecification {

		private final McpSchema.ResourceTemplate resourceTemplate;

		private final BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler;

		public SyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate,
				BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
			this.resourceTemplate = resourceTemplate;
			this.readHandler = readHandler;
		}

		public McpSchema.ResourceTemplate resourceTemplate() {
			return resourceTemplate;
		}

		public BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler() {
			return readHandler;
		}

	}

	/** Sync prompt specification. */
	public static final class SyncPromptSpecification {

		private final McpSchema.Prompt prompt;

		private final BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler;

		public SyncPromptSpecification(McpSchema.Prompt prompt,
				BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
			this.prompt = prompt;
			this.promptHandler = promptHandler;
		}

		public McpSchema.Prompt prompt() {
			return prompt;
		}

		public BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler() {
			return promptHandler;
		}

	}

	/** Sync completion specification. */
	public static final class SyncCompletionSpecification {

		private final McpSchema.CompleteReference referenceKey;

		private final BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler;

		public SyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
				BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler) {
			this.referenceKey = referenceKey;
			this.completionHandler = completionHandler;
		}

		public McpSchema.CompleteReference referenceKey() {
			return referenceKey;
		}

		public BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler() {
			return completionHandler;
		}

	}

}
