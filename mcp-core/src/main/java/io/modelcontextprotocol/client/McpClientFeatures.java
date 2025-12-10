
package io.modelcontextprotocol.client;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class McpClientFeatures {

	public static final class Async {

		private final McpSchema.Implementation clientInfo;

		private final McpSchema.ClientCapabilities clientCapabilities;

		private final Map<String, McpSchema.Root> roots;

		private final List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers;

		private final List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers;

		private final List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers;

		private final List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers;

		private final List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers;

		private final List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers;

		private final Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler;

		private final Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler;

		private final boolean enableCallToolSchemaCaching;

		public Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots,
				List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
				List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
				List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers,
				List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
				List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
				List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers,
				Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler,
				Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler,
				boolean enableCallToolSchemaCaching) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null,
							elicitationHandler != null ? new McpSchema.ClientCapabilities.Elicitation() : null);
			this.roots = roots != null ? new ConcurrentHashMap<>(roots) : new ConcurrentHashMap<>();
			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : Collections.emptyList();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers
					: Collections.emptyList();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers
					: Collections.emptyList();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers
					: Collections.emptyList();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : Collections.emptyList();
			this.progressConsumers = progressConsumers != null ? progressConsumers : Collections.emptyList();
			this.samplingHandler = samplingHandler;
			this.elicitationHandler = elicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
		}

		public static Async fromSync(Sync syncSpec) {
			List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Tool>> consumer : syncSpec.getToolsChangeConsumers()) {
				toolsChangeConsumers.add(
						t -> Mono.fromRunnable(() -> consumer.accept(t)).subscribeOn(Schedulers.boundedElastic()).then() // <--
																															// forza
																															// Mono<Void>
				);
			}

			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Resource>> consumer : syncSpec.getResourcesChangeConsumers()) {
				resourcesChangeConsumers.add(r -> Mono.fromRunnable(() -> consumer.accept(r))
						.subscribeOn(Schedulers.boundedElastic()).then());
			}

			List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.ResourceContents>> consumer : syncSpec.getResourcesUpdateConsumers()) {
				resourcesUpdateConsumers.add(r -> Mono.fromRunnable(() -> consumer.accept(r))
						.subscribeOn(Schedulers.boundedElastic()).then());
			}

			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Prompt>> consumer : syncSpec.getPromptsChangeConsumers()) {
				promptsChangeConsumers.add(p -> Mono.fromRunnable(() -> consumer.accept(p))
						.subscribeOn(Schedulers.boundedElastic()).then());
			}

			List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers = new ArrayList<>();
			for (Consumer<McpSchema.LoggingMessageNotification> consumer : syncSpec.getLoggingConsumers()) {
				loggingConsumers.add(l -> Mono.fromRunnable(() -> consumer.accept(l))
						.subscribeOn(Schedulers.boundedElastic()).then());
			}

			List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers = new ArrayList<>();
			for (Consumer<McpSchema.ProgressNotification> consumer : syncSpec.getProgressConsumers()) {
				progressConsumers.add(l -> Mono.fromRunnable(() -> consumer.accept(l))
						.subscribeOn(Schedulers.boundedElastic()).then());
			}

			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = r -> Mono
					.fromCallable(() -> syncSpec.getSamplingHandler().apply(r))
					.subscribeOn(Schedulers.boundedElastic());

			Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> elicitationHandler = r -> Mono
					.fromCallable(() -> syncSpec.getElicitationHandler().apply(r))
					.subscribeOn(Schedulers.boundedElastic());

			return new Async(syncSpec.getClientInfo(), syncSpec.getClientCapabilities(), syncSpec.getRoots(),
					toolsChangeConsumers, resourcesChangeConsumers, resourcesUpdateConsumers, promptsChangeConsumers,
					loggingConsumers, progressConsumers, samplingHandler, elicitationHandler,
					syncSpec.isEnableCallToolSchemaCaching());
		}

		// Getters
		public McpSchema.Implementation getClientInfo() {
			return clientInfo;
		}

		public McpSchema.ClientCapabilities getClientCapabilities() {
			return clientCapabilities;
		}

		public Map<String, McpSchema.Root> getRoots() {
			return roots;
		}

		public boolean getEnableCallToolSchemaCaching() {
			return enableCallToolSchemaCaching;
		}

		public Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> getSamplingHandler() {
			return samplingHandler;
		}

		public Function<McpSchema.ElicitRequest, Mono<McpSchema.ElicitResult>> getElicitationHandler() {
			return elicitationHandler;
		}

		public List<Function<List<McpSchema.Tool>, Mono<Void>>> getToolsChangeConsumers() {
			// TODO Auto-generated method stub
			return toolsChangeConsumers;
		}

		public List<Function<List<McpSchema.Resource>, Mono<Void>>> getResourcesChangeConsumers() {
			return resourcesChangeConsumers;
		}

		public List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> getResourcesUpdateConsumers() {
			return resourcesUpdateConsumers;
		}

		public List<Function<List<McpSchema.Prompt>, Mono<Void>>> getPromptsChangeConsumers() {
			return promptsChangeConsumers;
		}

		public List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> getLoggingConsumers() {
			return loggingConsumers;
		}

		public List<Function<McpSchema.ProgressNotification, Mono<Void>>> getProgressConsumers() {
			return progressConsumers;
		}

	}

	public static final class Sync {

		private final McpSchema.Implementation clientInfo;

		private final McpSchema.ClientCapabilities clientCapabilities;

		private final Map<String, McpSchema.Root> roots;

		private final List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers;

		private final List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers;

		private final List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers;

		private final List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers;

		private final List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers;

		private final List<Consumer<McpSchema.ProgressNotification>> progressConsumers;

		private final Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler;

		private final Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler;

		private final boolean enableCallToolSchemaCaching;

		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
				List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
				List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers,
				List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
				List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
				List<Consumer<McpSchema.ProgressNotification>> progressConsumers,
				Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler,
				Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> elicitationHandler,
				boolean enableCallToolSchemaCaching) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null,
							elicitationHandler != null ? new McpSchema.ClientCapabilities.Elicitation() : null);
			this.roots = roots != null ? new HashMap<>(roots) : new HashMap<>();
			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : Collections.emptyList();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers
					: Collections.emptyList();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers
					: Collections.emptyList();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers
					: Collections.emptyList();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : Collections.emptyList();
			this.progressConsumers = progressConsumers != null ? progressConsumers : Collections.emptyList();
			this.samplingHandler = samplingHandler;
			this.elicitationHandler = elicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
		}

		// Getters
		public McpSchema.Implementation getClientInfo() {
			return clientInfo;
		}

		public McpSchema.ClientCapabilities getClientCapabilities() {
			return clientCapabilities;
		}

		public Map<String, McpSchema.Root> getRoots() {
			return roots;
		}

		public List<Consumer<List<McpSchema.Tool>>> getToolsChangeConsumers() {
			return toolsChangeConsumers;
		}

		public List<Consumer<List<McpSchema.Resource>>> getResourcesChangeConsumers() {
			return resourcesChangeConsumers;
		}

		public List<Consumer<List<McpSchema.ResourceContents>>> getResourcesUpdateConsumers() {
			return resourcesUpdateConsumers;
		}

		public List<Consumer<List<McpSchema.Prompt>>> getPromptsChangeConsumers() {
			return promptsChangeConsumers;
		}

		public List<Consumer<McpSchema.LoggingMessageNotification>> getLoggingConsumers() {
			return loggingConsumers;
		}

		public List<Consumer<McpSchema.ProgressNotification>> getProgressConsumers() {
			return progressConsumers;
		}

		public Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> getSamplingHandler() {
			return samplingHandler;
		}

		public Function<McpSchema.ElicitRequest, McpSchema.ElicitResult> getElicitationHandler() {
			return elicitationHandler;
		}

		public boolean isEnableCallToolSchemaCaching() {
			return enableCallToolSchemaCaching;
		}

	}

}
