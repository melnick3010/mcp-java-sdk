/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.spec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Based on the <a href="http://www.jsonrpc.org/specification">JSON-RPC 2.0
 * specification</a> and the <a href=
 * "https://github.com/modelcontextprotocol/specification/blob/main/schema/2024-11-05/schema.ts">Model
 * Context Protocol Schema</a>.
 */
public final class McpSchema {

	private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private McpSchema() {
	}

	@Deprecated
	public static final String LATEST_PROTOCOL_VERSION = ProtocolVersions.MCP_2025_06_18;

	public static final String JSONRPC_VERSION = "2.0";

	public static final String FIRST_PAGE = null;

	// -----------------------------------------------------------------
	// Method Names
	// -----------------------------------------------------------------
	public static final String METHOD_INITIALIZE = "initialize";

	public static final String METHOD_NOTIFICATION_INITIALIZED = "notifications/initialized";

	public static final String METHOD_PING = "ping";

	public static final String METHOD_NOTIFICATION_PROGRESS = "notifications/progress";

	public static final String METHOD_TOOLS_LIST = "tools/list";

	public static final String METHOD_TOOLS_CALL = "tools/call";

	public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

	public static final String METHOD_RESOURCES_LIST = "resources/list";

	public static final String METHOD_RESOURCES_READ = "resources/read";

	public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

	public static final String METHOD_NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/updated";

	public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";

	public static final String METHOD_RESOURCES_SUBSCRIBE = "resources/subscribe";

	public static final String METHOD_RESOURCES_UNSUBSCRIBE = "resources/unsubscribe";

	public static final String METHOD_PROMPT_LIST = "prompts/list";

	public static final String METHOD_PROMPT_GET = "prompts/get";

	public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

	public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

	public static final String METHOD_LOGGING_SET_LEVEL = "logging/setLevel";

	public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

	public static final String METHOD_ROOTS_LIST = "roots/list";

	public static final String METHOD_NOTIFICATION_ROOTS_LIST_CHANGED = "notifications/roots/list_changed";

	public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";

	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	// -----------------------------------------------------------------
	// JSON-RPC Error Codes
	// -----------------------------------------------------------------
	public static final class ErrorCodes {

		public static final int PARSE_ERROR = -32700;

		public static final int INVALID_REQUEST = -32600;

		public static final int METHOD_NOT_FOUND = -32601;

		public static final int INVALID_PARAMS = -32602;

		public static final int INTERNAL_ERROR = -32603;

		public static final int RESOURCE_NOT_FOUND = -32002;

	}

	/**
	 * Base interface for MCP objects that include optional metadata in the `_meta` field.
	 */
	public interface Meta {

		/** Additional metadata related to this resource. */
		default Map<String, Object> meta() {
			return null;
		}

	}

	public interface Request extends Meta {

		default Object progressToken() {
			return (meta() != null && meta().containsKey("progressToken")) ? meta().get("progressToken") : null;
		}

	}

	public interface Result extends Meta {

	}

	public interface Notification extends Meta {

	}

	private static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<HashMap<String, Object>>() {
	};

	public static JSONRPCMessage deserializeJsonRpcMessage(McpJsonMapper jsonMapper, String jsonText)
			throws IOException {
		logger.debug("Received JSON message: {}", jsonText);
		Map<String, Object> map = jsonMapper.readValue(jsonText, MAP_TYPE_REF);
		if (map.containsKey("method") && map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCRequest.class);
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			return jsonMapper.convertValue(map, JSONRPCNotification.class);
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			return jsonMapper.convertValue(map, JSONRPCResponse.class);
		}
		throw new IllegalArgumentException("Cannot deserialize JSONRPCMessage: " + jsonText);
	}

	public interface JSONRPCMessage {

		String jsonrpc();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCRequest implements JSONRPCMessage {

		@JsonProperty("jsonrpc")
		private final String jsonrpc;

		@JsonProperty("method")
		private final String method;

		@JsonProperty("id")
		private final Object id;

		@JsonProperty("params")
		private final Object params;

		@JsonCreator
		public JSONRPCRequest(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("method") String method,
				@JsonProperty("id") Object id, @JsonProperty("params") Object params) {
			Assert.notNull(id, "MCP requests MUST include an ID - null IDs are not allowed");
			Assert.isTrue(id instanceof String || id instanceof Integer || id instanceof Long,
					"MCP requests MUST have an ID that is either a string or integer");
			Assert.isTrue("2.0".equals(jsonrpc), "jsonrpc must be \"2.0\"");
			this.jsonrpc = jsonrpc;
			this.method = method;
			this.id = id;
			this.params = params;
		}

		public String jsonrpc() {
			return jsonrpc;
		}

		public String method() {
			return method;
		}

		public Object id() {
			return id;
		}

		public Object params() {
			return params;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCNotification implements JSONRPCMessage {

		@JsonProperty("jsonrpc")
		private final String jsonrpc;

		@JsonProperty("method")
		private final String method;

		@JsonProperty("params")
		private final Object params;

		@JsonCreator
		public JSONRPCNotification(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("method") String method,
				@JsonProperty("params") Object params) {
			Assert.isTrue("2.0".equals(jsonrpc), "jsonrpc must be \"2.0\"");
			this.jsonrpc = jsonrpc;
			this.method = method;
			this.params = params;
		}

		public String jsonrpc() {
			return jsonrpc;
		}

		public String method() {
			return method;
		}

		public Object params() {
			return params;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JSONRPCResponse implements JSONRPCMessage {

		@JsonProperty("jsonrpc")
		private final String jsonrpc;

		@JsonProperty("id")
		private final Object id;

		@JsonProperty("result")
		private final Object result;

		@JsonProperty("error")
		private final JSONRPCError error;

		@JsonCreator
		public JSONRPCResponse(@JsonProperty("jsonrpc") String jsonrpc, @JsonProperty("id") Object id,
				@JsonProperty("result") Object result, @JsonProperty("error") JSONRPCError error) {
			Assert.isTrue("2.0".equals(jsonrpc), "jsonrpc must be \"2.0\"");
			this.jsonrpc = jsonrpc;
			this.id = id;
			this.result = result;
			this.error = error;
		}

		public String jsonrpc() {
			return jsonrpc;
		}

		public Object id() {
			return id;
		}

		public Object result() {
			return result;
		}

		public JSONRPCError error() {
			return error;
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class JSONRPCError {

			@JsonProperty("code")
			private final Integer code;

			@JsonProperty("message")
			private final String message;

			@JsonProperty("data")
			private final Object data;

			@JsonCreator
			public JSONRPCError(@JsonProperty("code") Integer code, @JsonProperty("message") String message,
					@JsonProperty("data") Object data) {
				this.code = code;
				this.message = message;
				this.data = data;
			}

			public Integer code() {
				return code;
			}

			public String message() {
				return message;
			}

			public Object data() {
				return data;
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class InitializeRequest implements Request {

		@JsonProperty("protocolVersion")
		private final String protocolVersion;

		@JsonProperty("capabilities")
		private final ClientCapabilities capabilities;

		@JsonProperty("clientInfo")
		private final Implementation clientInfo;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public InitializeRequest(@JsonProperty("protocolVersion") String protocolVersion,
				@JsonProperty("capabilities") ClientCapabilities capabilities,
				@JsonProperty("clientInfo") Implementation clientInfo,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.protocolVersion = protocolVersion;
			this.capabilities = capabilities;
			this.clientInfo = clientInfo;
			this._meta = meta;
		}

		public InitializeRequest(String protocolVersion, ClientCapabilities capabilities, Implementation clientInfo) {
			this(protocolVersion, capabilities, clientInfo, null);
		}

		public String protocolVersion() {
			return protocolVersion;
		}

		public ClientCapabilities capabilities() {
			return capabilities;
		}

		public Implementation clientInfo() {
			return clientInfo;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class InitializeResult implements Result {

		@JsonProperty("protocolVersion")
		private final String protocolVersion;

		@JsonProperty("capabilities")
		private final ServerCapabilities capabilities;

		@JsonProperty("serverInfo")
		private final Implementation serverInfo;

		@JsonProperty("instructions")
		private final String instructions;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public InitializeResult(@JsonProperty("protocolVersion") String protocolVersion,
				@JsonProperty("capabilities") ServerCapabilities capabilities,
				@JsonProperty("serverInfo") Implementation serverInfo,
				@JsonProperty("instructions") String instructions, @JsonProperty("_meta") Map<String, Object> meta) {
			this.protocolVersion = protocolVersion;
			this.capabilities = capabilities;
			this.serverInfo = serverInfo;
			this.instructions = instructions;
			this._meta = meta;
		}

		public InitializeResult(String protocolVersion, ServerCapabilities capabilities, Implementation serverInfo,
				String instructions) {
			this(protocolVersion, capabilities, serverInfo, instructions, null);
		}

		public String protocolVersion() {
			return protocolVersion;
		}

		public ServerCapabilities capabilities() {
			return capabilities;
		}

		public Implementation serverInfo() {
			return serverInfo;
		}

		public String instructions() {
			return instructions;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ClientCapabilities {

		@JsonProperty("experimental")
		private final Map<String, Object> experimental;

		@JsonProperty("roots")
		private final RootCapabilities roots;

		@JsonProperty("sampling")
		private final Sampling sampling;

		@JsonProperty("elicitation")
		private final Elicitation elicitation;

		@JsonCreator
		public ClientCapabilities(@JsonProperty("experimental") Map<String, Object> experimental,
				@JsonProperty("roots") RootCapabilities roots, @JsonProperty("sampling") Sampling sampling,
				@JsonProperty("elicitation") Elicitation elicitation) {
			this.experimental = experimental;
			this.roots = roots;
			this.sampling = sampling;
			this.elicitation = elicitation;
		}

		public Map<String, Object> experimental() {
			return experimental;
		}

		public RootCapabilities roots() {
			return roots;
		}

		public Sampling sampling() {
			return sampling;
		}

		public Elicitation elicitation() {
			return elicitation;
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class RootCapabilities {

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public RootCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
				this.listChanged = listChanged;
			}

			public Boolean listChanged() {
				return listChanged;
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public static final class Sampling {

			@JsonCreator
			public Sampling() {
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public static final class Elicitation {

			@JsonCreator
			public Elicitation() {
			}

		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Map<String, Object> experimental;

			private RootCapabilities roots;

			private Sampling sampling;

			private Elicitation elicitation;

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder roots(Boolean listChanged) {
				this.roots = new RootCapabilities(listChanged);
				return this;
			}

			public Builder sampling() {
				this.sampling = new Sampling();
				return this;
			}

			public Builder elicitation() {
				this.elicitation = new Elicitation();
				return this;
			}

			public ClientCapabilities build() {
				return new ClientCapabilities(experimental, roots, sampling, elicitation);
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ServerCapabilities {

		@JsonProperty("completions")
		private final CompletionCapabilities completions;

		@JsonProperty("experimental")
		private final Map<String, Object> experimental;

		@JsonProperty("logging")
		private final LoggingCapabilities logging;

		@JsonProperty("prompts")
		private final PromptCapabilities prompts;

		@JsonProperty("resources")
		private final ResourceCapabilities resources;

		@JsonProperty("tools")
		private final ToolCapabilities tools;

		@JsonCreator
		public ServerCapabilities(@JsonProperty("completions") CompletionCapabilities completions,
				@JsonProperty("experimental") Map<String, Object> experimental,
				@JsonProperty("logging") LoggingCapabilities logging,
				@JsonProperty("prompts") PromptCapabilities prompts,
				@JsonProperty("resources") ResourceCapabilities resources,
				@JsonProperty("tools") ToolCapabilities tools) {
			this.completions = completions;
			this.experimental = experimental;
			this.logging = logging;
			this.prompts = prompts;
			this.resources = resources;
			this.tools = tools;
		}

		public CompletionCapabilities completions() {
			return completions;
		}

		public Map<String, Object> experimental() {
			return experimental;
		}

		public LoggingCapabilities logging() {
			return logging;
		}

		public PromptCapabilities prompts() {
			return prompts;
		}

		public ResourceCapabilities resources() {
			return resources;
		}

		public ToolCapabilities tools() {
			return tools;
		}

		public Builder mutate() {
			Builder b = new Builder();
			b.completions = completions;
			b.experimental = experimental;
			b.logging = logging;
			b.prompts = prompts;
			b.resources = resources;
			b.tools = tools;
			return b;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private CompletionCapabilities completions;

			private Map<String, Object> experimental;

			private LoggingCapabilities logging;

			private PromptCapabilities prompts;

			private ResourceCapabilities resources;

			private ToolCapabilities tools;

			public Builder completions() {
				this.completions = new CompletionCapabilities();
				return this;
			}

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder logging() {
				this.logging = new LoggingCapabilities();
				return this;
			}

			public Builder prompts(Boolean listChanged) {
				this.prompts = new PromptCapabilities(listChanged);
				return this;
			}

			public Builder resources(Boolean subscribe, Boolean listChanged) {
				this.resources = new ResourceCapabilities(subscribe, listChanged);
				return this;
			}

			public Builder tools(Boolean listChanged) {
				this.tools = new ToolCapabilities(listChanged);
				return this;
			}

			public ServerCapabilities build() {
				return new ServerCapabilities(completions, experimental, logging, prompts, resources, tools);
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public static final class CompletionCapabilities {

			@JsonCreator
			public CompletionCapabilities() {
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		public static final class LoggingCapabilities {

			@JsonCreator
			public LoggingCapabilities() {
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class PromptCapabilities {

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public PromptCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
				this.listChanged = listChanged;
			}

			public Boolean listChanged() {
				return listChanged;
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class ResourceCapabilities {

			@JsonProperty("subscribe")
			private final Boolean subscribe;

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public ResourceCapabilities(@JsonProperty("subscribe") Boolean subscribe,
					@JsonProperty("listChanged") Boolean listChanged) {
				this.subscribe = subscribe;
				this.listChanged = listChanged;
			}

			public Boolean subscribe() {
				return subscribe;
			}

			public Boolean listChanged() {
				return listChanged;
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class ToolCapabilities {

			@JsonProperty("listChanged")
			private final Boolean listChanged;

			@JsonCreator
			public ToolCapabilities(@JsonProperty("listChanged") Boolean listChanged) {
				this.listChanged = listChanged;
			}

			public Boolean listChanged() {
				return listChanged;
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Implementation implements Identifier {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("version")
		private final String version;

		@JsonCreator
		public Implementation(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("version") String version) {
			this.name = name;
			this.title = title;
			this.version = version;
		}

		public Implementation(String name, String version) {
			this(name, null, version);
		}

		public String name() {
			return name;
		}

		public String title() {
			return title;
		}

		public String version() {
			return version;
		}

	}

	public enum Role {

		@JsonProperty("user")
		USER, @JsonProperty("assistant")
		ASSISTANT

	}

	public interface Annotated {

		Annotations annotations();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Annotations {

		@JsonProperty("audience")
		private final List<Role> audience;

		@JsonProperty("priority")
		private final Double priority;

		@JsonProperty("lastModified")
		private final String lastModified;

		@JsonCreator
		public Annotations(@JsonProperty("audience") List<Role> audience, @JsonProperty("priority") Double priority,
				@JsonProperty("lastModified") String lastModified) {
			this.audience = audience;
			this.priority = priority;
			this.lastModified = lastModified;
		}

		public Annotations(List<Role> audience, Double priority) {
			this(audience, priority, null);
		}

		public List<Role> audience() {
			return audience;
		}

		public Double priority() {
			return priority;
		}

		public String lastModified() {
			return lastModified;
		}

	}

	public interface ResourceContent extends Identifier, Annotated, Meta {

		String uri();

		String description();

		String mimeType();

		Long size();

	}

	public interface Identifier {

		String name();

		String title();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Resource implements ResourceContent {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("size")
		private final Long size;

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public Resource(@JsonProperty("uri") String uri, @JsonProperty("name") String name,
				@JsonProperty("title") String title, @JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size,
				@JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.name = name;
			this.title = title;
			this.description = description;
			this.mimeType = mimeType;
			this.size = size;
			this.annotations = annotations;
			this._meta = meta;
		}

		@Deprecated
		public Resource(String uri, String name, String title, String description, String mimeType, Long size,
				Annotations annotations) {
			this(uri, name, title, description, mimeType, size, annotations, null);
		}

		@Deprecated
		public Resource(String uri, String name, String description, String mimeType, Long size,
				Annotations annotations) {
			this(uri, name, null, description, mimeType, size, annotations, null);
		}

		@Deprecated
		public Resource(String uri, String name, String description, String mimeType, Annotations annotations) {
			this(uri, name, null, description, mimeType, null, annotations, null);
		}

		public String uri() {
			return uri;
		}

		public String name() {
			return name;
		}

		public String title() {
			return title;
		}

		public String description() {
			return description;
		}

		public String mimeType() {
			return mimeType;
		}

		public Long size() {
			return size;
		}

		public Annotations annotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String uri, name, title, description, mimeType;

			private Long size;

			private Annotations annotations;

			private Map<String, Object> meta;

			public Builder uri(String v) {
				this.uri = v;
				return this;
			}

			public Builder name(String v) {
				this.name = v;
				return this;
			}

			public Builder title(String v) {
				this.title = v;
				return this;
			}

			public Builder description(String v) {
				this.description = v;
				return this;
			}

			public Builder mimeType(String v) {
				this.mimeType = v;
				return this;
			}

			public Builder size(Long v) {
				this.size = v;
				return this;
			}

			public Builder annotations(Annotations v) {
				this.annotations = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public Resource build() {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");
				return new Resource(uri, name, title, description, mimeType, size, annotations, meta);
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceTemplate implements Annotated, Identifier, Meta {

		@JsonProperty("uriTemplate")
		private final String uriTemplate;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ResourceTemplate(@JsonProperty("uriTemplate") String uriTemplate, @JsonProperty("name") String name,
				@JsonProperty("title") String title, @JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("annotations") Annotations annotations,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.uriTemplate = uriTemplate;
			this.name = name;
			this.title = title;
			this.description = description;
			this.mimeType = mimeType;
			this.annotations = annotations;
			this._meta = meta;
		}

		public ResourceTemplate(String uriTemplate, String name, String title, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, title, description, mimeType, annotations, null);
		}

		public ResourceTemplate(String uriTemplate, String name, String description, String mimeType,
				Annotations annotations) {
			this(uriTemplate, name, null, description, mimeType, annotations, null);
		}

		public String uriTemplate() {
			return uriTemplate;
		}

		public String name() {
			return name;
		}

		public String title() {
			return title;
		}

		public String description() {
			return description;
		}

		public String mimeType() {
			return mimeType;
		}

		public Annotations annotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String uriTemplate, name, title, description, mimeType;

			private Annotations annotations;

			private Map<String, Object> meta;

			public Builder uriTemplate(String v) {
				this.uriTemplate = v;
				return this;
			}

			public Builder name(String v) {
				this.name = v;
				return this;
			}

			public Builder title(String v) {
				this.title = v;
				return this;
			}

			public Builder description(String v) {
				this.description = v;
				return this;
			}

			public Builder mimeType(String v) {
				this.mimeType = v;
				return this;
			}

			public Builder annotations(Annotations v) {
				this.annotations = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public ResourceTemplate build() {
				Assert.hasText(uriTemplate, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");
				return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, meta);
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListResourcesResult implements Result {

		@JsonProperty("resources")
		private final List<Resource> resources;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ListResourcesResult(@JsonProperty("resources") List<Resource> resources,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.resources = resources;
			this.nextCursor = nextCursor;
			this._meta = meta;
		}

		public ListResourcesResult(List<Resource> resources, String nextCursor) {
			this(resources, nextCursor, null);
		}

		public List<Resource> resources() {
			return resources;
		}

		public String nextCursor() {
			return nextCursor;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListResourceTemplatesResult implements Result {

		@JsonProperty("resourceTemplates")
		private final List<ResourceTemplate> resourceTemplates;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ListResourceTemplatesResult(@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.resourceTemplates = resourceTemplates != null
					? Collections.unmodifiableList(new ArrayList<ResourceTemplate>(resourceTemplates))
					: Collections.<ResourceTemplate>emptyList();
			this.nextCursor = nextCursor;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor) {
			this(resourceTemplates, nextCursor, null);
		}

		public List<ResourceTemplate> getResourceTemplates() {
			return resourceTemplates;
		}

		public String getNextCursor() {
			return nextCursor;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ReadResourceRequest implements Request {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ReadResourceRequest(@JsonProperty("uri") String uri, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public ReadResourceRequest(String uri) {
			this(uri, null);
		}

		public String getUri() {
			return uri;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ReadResourceResult implements Result {

		@JsonProperty("contents")
		private final List<ResourceContents> contents;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ReadResourceResult(@JsonProperty("contents") List<ResourceContents> contents,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.contents = contents != null ? Collections.unmodifiableList(new ArrayList<ResourceContents>(contents))
					: Collections.<ResourceContents>emptyList();
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public ReadResourceResult(List<ResourceContents> contents) {
			this(contents, null);
		}

		public List<ResourceContents> getContents() {
			return contents;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SubscribeRequest implements Request {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public SubscribeRequest(@JsonProperty("uri") String uri, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public SubscribeRequest(String uri) {
			this(uri, null);
		}

		public String getUri() {
			return uri;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class UnsubscribeRequest implements Request {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public UnsubscribeRequest(@JsonProperty("uri") String uri, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public UnsubscribeRequest(String uri) {
			this(uri, null);
		}

		public String getUri() {
			return uri;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public interface ResourceContents extends Meta {

		String uri();

		String mimeType();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TextResourceContents implements ResourceContents {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("text")
		private final String text;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public TextResourceContents(@JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("text") String text, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.mimeType = mimeType;
			this.text = text;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public TextResourceContents(String uri, String mimeType, String text) {
			this(uri, mimeType, text, null);
		}

		public String uri() {
			return uri;
		}

		public String mimeType() {
			return mimeType;
		}

		public String getText() {
			return text;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class BlobResourceContents implements ResourceContents {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("blob")
		private final String blob;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public BlobResourceContents(@JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("blob") String blob, @JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.mimeType = mimeType;
			this.blob = blob;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public BlobResourceContents(String uri, String mimeType, String blob) {
			this(uri, mimeType, blob, null);
		}

		public String uri() {
			return uri;
		}

		public String mimeType() {
			return mimeType;
		}

		public String getBlob() {
			return blob;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Prompt implements Identifier {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("arguments")
		private final List<PromptArgument> arguments;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public Prompt(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("arguments") List<PromptArgument> arguments,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.title = title;
			this.description = description;
			this.arguments = arguments != null ? Collections.unmodifiableList(new ArrayList<PromptArgument>(arguments))
					: Collections.unmodifiableList(new ArrayList<PromptArgument>());
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public Prompt(String name, String description, List<PromptArgument> arguments) {
			this(name, null, description, arguments != null ? arguments : new ArrayList<PromptArgument>(), null);
		}

		public Prompt(String name, String title, String description, List<PromptArgument> arguments) {
			this(name, title, description, arguments != null ? arguments : new ArrayList<PromptArgument>(), null);
		}

		public String name() {
			return name;
		}

		public String title() {
			return title;
		}

		public String description() {
			return description;
		}

		public List<PromptArgument> arguments() {
			return arguments;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptArgument implements Identifier {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("required")
		private final Boolean required;

		@JsonCreator
		public PromptArgument(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description, @JsonProperty("required") Boolean required) {
			this.name = name;
			this.title = title;
			this.description = description;
			this.required = required;
		}

		public PromptArgument(String name, String description, Boolean required) {
			this(name, null, description, required);
		}

		public String name() {
			return name;
		}

		public String title() {
			return title;
		}

		public String description() {
			return description;
		}

		public Boolean required() {
			return required;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptMessage {

		@JsonProperty("role")
		private final Role role;

		@JsonProperty("content")
		private final Content content;

		@JsonCreator
		public PromptMessage(@JsonProperty("role") Role role, @JsonProperty("content") Content content) {
			this.role = role;
			this.content = content;
		}

		public Role getRole() {
			return role;
		}

		public Content getContent() {
			return content;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListPromptsResult implements Result {

		@JsonProperty("prompts")
		private final List<Prompt> prompts;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ListPromptsResult(@JsonProperty("prompts") List<Prompt> prompts,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("_meta") Map<String, Object> meta) {
			this.prompts = prompts != null ? Collections.unmodifiableList(new ArrayList<Prompt>(prompts))
					: Collections.unmodifiableList(new ArrayList<Prompt>());
			this.nextCursor = nextCursor;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public ListPromptsResult(List<Prompt> prompts, String nextCursor) {
			this(prompts, nextCursor, null);
		}

		public List<Prompt> getPrompts() {
			return prompts;
		}

		public String getNextCursor() {
			return nextCursor;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetPromptRequest implements Request {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("arguments")
		private final Map<String, Object> arguments;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public GetPromptRequest(@JsonProperty("name") String name,
				@JsonProperty("arguments") Map<String, Object> arguments,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.arguments = arguments != null
					? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments))
					: Collections.unmodifiableMap(new LinkedHashMap<String, Object>());
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public GetPromptRequest(String name, Map<String, Object> arguments) {
			this(name, arguments, null);
		}

		public String getName() {
			return name;
		}

		public Map<String, Object> getArguments() {
			return arguments;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class GetPromptResult implements Result {

		@JsonProperty("description")
		private final String description;

		@JsonProperty("messages")
		private final List<PromptMessage> messages;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public GetPromptResult(@JsonProperty("description") String description,
				@JsonProperty("messages") List<PromptMessage> messages,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.description = description;
			this.messages = messages != null ? Collections.unmodifiableList(new ArrayList<PromptMessage>(messages))
					: Collections.unmodifiableList(new ArrayList<PromptMessage>());
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public GetPromptResult(String description, List<PromptMessage> messages) {
			this(description, messages, null);
		}

		public String getDescription() {
			return description;
		}

		public List<PromptMessage> getMessages() {
			return messages;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListToolsResult implements Result {

		@JsonProperty("tools")
		private final List<Tool> tools;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ListToolsResult(@JsonProperty("tools") List<Tool> tools, @JsonProperty("nextCursor") String nextCursor,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.tools = tools != null ? Collections.unmodifiableList(new ArrayList<Tool>(tools))
					: Collections.unmodifiableList(new ArrayList<Tool>());
			this.nextCursor = nextCursor;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public ListToolsResult(List<Tool> tools, String nextCursor) {
			this(tools, nextCursor, null);
		}

		public List<Tool> getTools() {
			return tools;
		}

		public String getNextCursor() {
			return nextCursor;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class JsonSchema {

		@JsonProperty("type")
		private final String type;

		@JsonProperty("properties")
		private final Map<String, Object> properties;

		@JsonProperty("required")
		private final List<String> required;

		@JsonProperty("additionalProperties")
		private final Boolean additionalProperties;

		@JsonProperty("$defs")
		private final Map<String, Object> defs;

		@JsonProperty("definitions")
		private final Map<String, Object> definitions;

		@JsonCreator
		public JsonSchema(@JsonProperty("type") String type, @JsonProperty("properties") Map<String, Object> properties,
				@JsonProperty("required") List<String> required,
				@JsonProperty("additionalProperties") Boolean additionalProperties,
				@JsonProperty("$defs") Map<String, Object> defs,
				@JsonProperty("definitions") Map<String, Object> definitions) {
			this.type = type;
			this.properties = properties != null
					? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(properties))
					: Collections.unmodifiableMap(new LinkedHashMap<String, Object>());
			this.required = required != null ? Collections.unmodifiableList(new ArrayList<String>(required))
					: Collections.unmodifiableList(new ArrayList<String>());
			this.additionalProperties = additionalProperties;
			this.defs = defs;
			this.definitions = definitions;
		}

		public String getType() {
			return type;
		}

		public Map<String, Object> getProperties() {
			return properties;
		}

		public List<String> getRequired() {
			return required;
		}

		public Boolean getAdditionalProperties() {
			return additionalProperties;
		}

		public Map<String, Object> getDefs() {
			return defs;
		}

		public Map<String, Object> getDefinitions() {
			return definitions;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ToolAnnotations {

		@JsonProperty("title")
		private final String title;

		@JsonProperty("readOnlyHint")
		private final Boolean readOnlyHint;

		@JsonProperty("destructiveHint")
		private final Boolean destructiveHint;

		@JsonProperty("idempotentHint")
		private final Boolean idempotentHint;

		@JsonProperty("openWorldHint")
		private final Boolean openWorldHint;

		@JsonProperty("returnDirect")
		private final Boolean returnDirect;

		@JsonCreator
		public ToolAnnotations(@JsonProperty("title") String title, @JsonProperty("readOnlyHint") Boolean readOnlyHint,
				@JsonProperty("destructiveHint") Boolean destructiveHint,
				@JsonProperty("idempotentHint") Boolean idempotentHint,
				@JsonProperty("openWorldHint") Boolean openWorldHint,
				@JsonProperty("returnDirect") Boolean returnDirect) {
			this.title = title;
			this.readOnlyHint = readOnlyHint;
			this.destructiveHint = destructiveHint;
			this.idempotentHint = idempotentHint;
			this.openWorldHint = openWorldHint;
			this.returnDirect = returnDirect;
		}

		public String getTitle() {
			return title;
		}

		public Boolean getReadOnlyHint() {
			return readOnlyHint;
		}

		public Boolean getDestructiveHint() {
			return destructiveHint;
		}

		public Boolean getIdempotentHint() {
			return idempotentHint;
		}

		public Boolean getOpenWorldHint() {
			return openWorldHint;
		}

		public Boolean getReturnDirect() {
			return returnDirect;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Tool {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("inputSchema")
		private final JsonSchema inputSchema;

		@JsonProperty("outputSchema")
		private final Map<String, Object> outputSchema;

		@JsonProperty("annotations")
		private final ToolAnnotations annotations;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public Tool(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description, @JsonProperty("inputSchema") JsonSchema inputSchema,
				@JsonProperty("outputSchema") Map<String, Object> outputSchema,
				@JsonProperty("annotations") ToolAnnotations annotations,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.title = title;
			this.description = description;
			this.inputSchema = inputSchema;
			this.outputSchema = outputSchema != null
					? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(outputSchema)) : null;
			this.annotations = annotations;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public String getName() {
			return name;
		}

		public String getTitle() {
			return title;
		}

		public String getDescription() {
			return description;
		}

		public JsonSchema getInputSchema() {
			return inputSchema;
		}

		public Map<String, Object> getOutputSchema() {
			return outputSchema;
		}

		public ToolAnnotations getAnnotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name, title, description;

			private JsonSchema inputSchema;

			private Map<String, Object> outputSchema;

			private ToolAnnotations annotations;

			private Map<String, Object> meta;

			public Builder name(String v) {
				this.name = v;
				return this;
			}

			public Builder title(String v) {
				this.title = v;
				return this;
			}

			public Builder description(String v) {
				this.description = v;
				return this;
			}

			public Builder inputSchema(JsonSchema v) {
				this.inputSchema = v;
				return this;
			}

			public Builder inputSchema(McpJsonMapper jsonMapper, String schema) {
				this.inputSchema = parseSchema(jsonMapper, schema);
				return this;
			}

			public Builder outputSchema(Map<String, Object> v) {
				this.outputSchema = v;
				return this;
			}

			public Builder outputSchema(McpJsonMapper jsonMapper, String schema) {
				this.outputSchema = schemaToMap(jsonMapper, schema);
				return this;
			}

			public Builder annotations(ToolAnnotations v) {
				this.annotations = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public Tool build() {
				Assert.hasText(name, "name must not be empty");
				return new Tool(name, title, description, inputSchema, outputSchema, annotations, meta);
			}

		}

	}

	private static Map<String, Object> schemaToMap(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, MAP_TYPE_REF);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}

	private static JsonSchema parseSchema(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, JsonSchema.class);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CallToolRequest implements Request {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("arguments")
		private final Map<String, Object> arguments;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public CallToolRequest(@JsonProperty("name") String name,
				@JsonProperty("arguments") Map<String, Object> arguments,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.arguments = arguments != null
					? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments))
					: Collections.unmodifiableMap(new LinkedHashMap<String, Object>());
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public CallToolRequest(McpJsonMapper jsonMapper, String name, String jsonArguments) {
			this(name, parseJsonArguments(jsonMapper, jsonArguments), null);
		}

		public CallToolRequest(String name, Map<String, Object> arguments) {
			this(name, arguments, null);
		}

		private static Map<String, Object> parseJsonArguments(McpJsonMapper jsonMapper, String jsonArguments) {
			try {
				return jsonMapper.readValue(jsonArguments, MAP_TYPE_REF);
			}
			catch (IOException e) {
				throw new IllegalArgumentException("Invalid arguments: " + jsonArguments, e);
			}
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name;

			private Map<String, Object> arguments;

			private Map<String, Object> meta;

			public Builder name(String v) {
				this.name = v;
				return this;
			}

			public Builder arguments(Map<String, Object> v) {
				this.arguments = v;
				return this;
			}

			public Builder arguments(McpJsonMapper jsonMapper, String jsonArguments) {
				this.arguments = parseJsonArguments(jsonMapper, jsonArguments);
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public Builder progressToken(Object token) {
				if (this.meta == null)
					this.meta = new HashMap<String, Object>();
				this.meta.put("progressToken", token);
				return this;
			}

			public CallToolRequest build() {
				Assert.hasText(name, "name must not be empty");
				return new CallToolRequest(name, arguments, meta);
			}

		}

		public String getName() {
			return name;
		}

		public Map<String, Object> getArguments() {
			return arguments;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CallToolResult implements Result {

		@JsonProperty("content")
		private final List<Content> content;

		@JsonProperty("isError")
		private final Boolean isError;

		@JsonProperty("structuredContent")
		private final Object structuredContent;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public CallToolResult(@JsonProperty("content") List<Content> content, @JsonProperty("isError") Boolean isError,
				@JsonProperty("structuredContent") Object structuredContent,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.content = content != null ? Collections.unmodifiableList(new ArrayList<Content>(content))
					: Collections.unmodifiableList(new ArrayList<Content>());
			this.isError = isError;
			this.structuredContent = structuredContent;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		@Deprecated
		public CallToolResult(List<Content> content, Boolean isError) {
			this(content, isError, (Object) null, null);
		}

		@Deprecated
		public CallToolResult(List<Content> content, Boolean isError, Map<String, Object> structuredContent) {
			this(content, isError, (Object) structuredContent, null);
		}

		@Deprecated
		public CallToolResult(String content, Boolean isError) {
			this(Collections.<Content>singletonList(new TextContent(content)), isError, null);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<Content> content = new ArrayList<Content>();

			private Boolean isError = false;

			private Object structuredContent;

			private Map<String, Object> meta;

			public Builder content(List<Content> v) {
				Assert.notNull(v, "content must not be null");
				this.content = v;
				return this;
			}

			public Builder structuredContent(Object v) {
				Assert.notNull(v, "structuredContent must not be null");
				this.structuredContent = v;
				return this;
			}

			public Builder structuredContent(McpJsonMapper jsonMapper, String v) {
				Assert.hasText(v, "structuredContent must not be empty");
				try {
					this.structuredContent = jsonMapper.readValue(v, MAP_TYPE_REF);
				}
				catch (IOException e) {
					throw new IllegalArgumentException("Invalid structured content: " + v, e);
				}
				return this;
			}

			public Builder textContent(List<String> texts) {
				Assert.notNull(texts, "textContent must not be null");
				for (String s : texts)
					this.content.add(new TextContent(s));
				return this;
			}

			public Builder addContent(Content c) {
				Assert.notNull(c, "contentItem must not be null");
				if (this.content == null)
					this.content = new ArrayList<Content>();
				this.content.add(c);
				return this;
			}

			public Builder addTextContent(String text) {
				Assert.notNull(text, "text must not be null");
				return addContent(new TextContent(text));
			}

			public Builder isError(Boolean v) {
				Assert.notNull(v, "isError must not be null");
				this.isError = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public CallToolResult build() {
				return new CallToolResult(content, isError, structuredContent, meta);
			}

		}

		public List<Content> getContent() {
			return content;
		}

		public Boolean getIsError() {
			return isError;
		}

		public Object getStructuredContent() {
			return structuredContent;
		}

		public Map<String, Object> meta() {
			return _meta;
		}
	
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CallToolResult that = (CallToolResult) o;
			return Objects.equals(content, that.content) &&
					Objects.equals(isError, that.isError) &&
					Objects.equals(structuredContent, that.structuredContent) &&
					Objects.equals(_meta, that._meta);
		}
	
		@Override
		public int hashCode() {
			return Objects.hash(content, isError, structuredContent, _meta);
		}
	
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ModelPreferences {

		@JsonProperty("hints")
		private final List<ModelHint> hints;

		@JsonProperty("costPriority")
		private final Double costPriority;

		@JsonProperty("speedPriority")
		private final Double speedPriority;

		@JsonProperty("intelligencePriority")
		private final Double intelligencePriority;

		@JsonCreator
		public ModelPreferences(@JsonProperty("hints") List<ModelHint> hints,
				@JsonProperty("costPriority") Double costPriority, @JsonProperty("speedPriority") Double speedPriority,
				@JsonProperty("intelligencePriority") Double intelligencePriority) {
			this.hints = hints != null ? Collections.unmodifiableList(new ArrayList<ModelHint>(hints))
					: Collections.unmodifiableList(new ArrayList<ModelHint>());
			this.costPriority = costPriority;
			this.speedPriority = speedPriority;
			this.intelligencePriority = intelligencePriority;
		}

		public List<ModelHint> getHints() {
			return hints;
		}

		public Double getCostPriority() {
			return costPriority;
		}

		public Double getSpeedPriority() {
			return speedPriority;
		}

		public Double getIntelligencePriority() {
			return intelligencePriority;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<ModelHint> hints;

			private Double costPriority;

			private Double speedPriority;

			private Double intelligencePriority;

			public Builder hints(List<ModelHint> v) {
				this.hints = v;
				return this;
			}

			public Builder addHint(String name) {
				if (this.hints == null)
					this.hints = new ArrayList<ModelHint>();
				this.hints.add(new ModelHint(name));
				return this;
			}

			public Builder costPriority(Double v) {
				this.costPriority = v;
				return this;
			}

			public Builder speedPriority(Double v) {
				this.speedPriority = v;
				return this;
			}

			public Builder intelligencePriority(Double v) {
				this.intelligencePriority = v;
				return this;
			}

			public ModelPreferences build() {
				return new ModelPreferences(hints, costPriority, speedPriority, intelligencePriority);
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ModelHint {

		@JsonProperty("name")
		private final String name;

		@JsonCreator
		public ModelHint(@JsonProperty("name") String name) {
			this.name = name;
		}

		public static ModelHint of(String name) {
			return new ModelHint(name);
		}

		public String getName() {
			return name;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SamplingMessage {

		@JsonProperty("role")
		private final Role role;

		@JsonProperty("content")
		private final Content content;

		@JsonCreator
		public SamplingMessage(@JsonProperty("role") Role role, @JsonProperty("content") Content content) {
			this.role = role;
			this.content = content;
		}

		public Role getRole() {
			return role;
		}

		public Content getContent() {
			return content;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CreateMessageRequest implements Request {

		@JsonProperty("messages")
		private final List<SamplingMessage> messages;

		@JsonProperty("modelPreferences")
		private final ModelPreferences modelPreferences;

		@JsonProperty("systemPrompt")
		private final String systemPrompt;

		@JsonProperty("includeContext")
		private final ContextInclusionStrategy includeContext;

		@JsonProperty("temperature")
		private final Double temperature;

		@JsonProperty("maxTokens")
		private final Integer maxTokens;

		@JsonProperty("stopSequences")
		private final List<String> stopSequences;

		@JsonProperty("metadata")
		private final Map<String, Object> metadata;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public CreateMessageRequest(@JsonProperty("messages") List<SamplingMessage> messages,
				@JsonProperty("modelPreferences") ModelPreferences modelPreferences,
				@JsonProperty("systemPrompt") String systemPrompt,
				@JsonProperty("includeContext") ContextInclusionStrategy includeContext,
				@JsonProperty("temperature") Double temperature, @JsonProperty("maxTokens") Integer maxTokens,
				@JsonProperty("stopSequences") List<String> stopSequences,
				@JsonProperty("metadata") Map<String, Object> metadata,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.messages = messages != null ? Collections.unmodifiableList(new ArrayList<SamplingMessage>(messages))
					: Collections.unmodifiableList(new ArrayList<SamplingMessage>());
			this.modelPreferences = modelPreferences;
			this.systemPrompt = systemPrompt;
			this.includeContext = includeContext;
			this.temperature = temperature;
			this.maxTokens = maxTokens;
			this.stopSequences = stopSequences != null
					? Collections.unmodifiableList(new ArrayList<String>(stopSequences)) : null;
			this.metadata = metadata != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata))
					: null;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences,
				String systemPrompt, ContextInclusionStrategy includeContext, Double temperature, Integer maxTokens,
				List<String> stopSequences, Map<String, Object> metadata) {
			this(messages, modelPreferences, systemPrompt, includeContext, temperature, maxTokens, stopSequences,
					metadata, null);
		}

		public enum ContextInclusionStrategy {

			@JsonProperty("none")
			NONE, @JsonProperty("thisServer")
			THIS_SERVER, @JsonProperty("allServers")
			ALL_SERVERS

		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private List<SamplingMessage> messages;

			private ModelPreferences modelPreferences;

			private String systemPrompt;

			private ContextInclusionStrategy includeContext;

			private Double temperature;

			private Integer maxTokens;

			private List<String> stopSequences;

			private Map<String, Object> metadata;

			private Map<String, Object> meta;

			public Builder messages(List<SamplingMessage> v) {
				this.messages = v;
				return this;
			}

			public Builder modelPreferences(ModelPreferences v) {
				this.modelPreferences = v;
				return this;
			}

			public Builder systemPrompt(String v) {
				this.systemPrompt = v;
				return this;
			}

			public Builder includeContext(ContextInclusionStrategy v) {
				this.includeContext = v;
				return this;
			}

			public Builder temperature(Double v) {
				this.temperature = v;
				return this;
			}

			public Builder maxTokens(int v) {
				this.maxTokens = v;
				return this;
			}

			public Builder stopSequences(List<String> v) {
				this.stopSequences = v;
				return this;
			}

			public Builder metadata(Map<String, Object> v) {
				this.metadata = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public Builder progressToken(Object token) {
				if (this.meta == null)
					this.meta = new HashMap<String, Object>();
				this.meta.put("progressToken", token);
				return this;
			}

			public CreateMessageRequest build() {
				return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
						maxTokens, stopSequences, metadata, meta);
			}

		}

		public List<SamplingMessage> getMessages() {
			return messages;
		}

		public ModelPreferences getModelPreferences() {
			return modelPreferences;
		}

		public String getSystemPrompt() {
			return systemPrompt;
		}

		public ContextInclusionStrategy getIncludeContext() {
			return includeContext;
		}

		public Double getTemperature() {
			return temperature;
		}

		public Integer getMaxTokens() {
			return maxTokens;
		}

		public List<String> getStopSequences() {
			return stopSequences;
		}

		public Map<String, Object> getMetadata() {
			return metadata;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CreateMessageResult implements Result {

		@JsonProperty("role")
		private final Role role;

		@JsonProperty("content")
		private final Content content;

		@JsonProperty("model")
		private final String model;

		@JsonProperty("stopReason")
		private final StopReason stopReason;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public CreateMessageResult(@JsonProperty("role") Role role, @JsonProperty("content") Content content,
				@JsonProperty("model") String model, @JsonProperty("stopReason") StopReason stopReason,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.role = role;
			this.content = content;
			this.model = model;
			this.stopReason = stopReason;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public CreateMessageResult(Role role, Content content, String model, StopReason stopReason) {
			this(role, content, model, stopReason, null);
		}

		public enum StopReason {

			@JsonProperty("endTurn")
			END_TURN("endTurn"), @JsonProperty("stopSequence")
			STOP_SEQUENCE("stopSequence"), @JsonProperty("maxTokens")
			MAX_TOKENS("maxTokens"), @JsonProperty("unknown")
			UNKNOWN("unknown");

			private final String value;

			StopReason(String v) {
				this.value = v;
			}

			@JsonCreator
			private static StopReason of(String v) {
				for (StopReason sr : StopReason.values()) {
					if (sr.value.equals(v))
						return sr;
				}
				return StopReason.UNKNOWN;
			}

			@Override
			public String toString() {
				return value;
			}

		}

		public Role getRole() {
			return role;
		}

		public Content getContent() {
			return content;
		}

		public String getModel() {
			return model;
		}

		public StopReason getStopReason() {
			return stopReason;
		}

		public Map<String, Object> meta() {
			return _meta;
		}
	
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			CreateMessageResult that = (CreateMessageResult) o;
			return role == that.role &&
					Objects.equals(content, that.content) &&
					Objects.equals(model, that.model) &&
					stopReason == that.stopReason &&
					Objects.equals(_meta, that._meta);
		}
	
		@Override
		public int hashCode() {
			return Objects.hash(role, content, model, stopReason, _meta);
		}
	
		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Role role = Role.ASSISTANT;

			private Content content;

			private String model;

			private StopReason stopReason = StopReason.END_TURN;

			private Map<String, Object> meta;

			public Builder role(Role v) {
				this.role = v;
				return this;
			}

			public Builder content(Content v) {
				this.content = v;
				return this;
			}

			public Builder model(String v) {
				this.model = v;
				return this;
			}

			public Builder stopReason(StopReason v) {
				this.stopReason = v;
				return this;
			}

			public Builder message(String message) {
				this.content = new TextContent(message);
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public CreateMessageResult build() {
				return new CreateMessageResult(role, content, model, stopReason, meta);
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitRequest implements Request {

		@JsonProperty("message")
		private final String message;

		@JsonProperty("requestedSchema")
		private final Map<String, Object> requestedSchema;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ElicitRequest(@JsonProperty("message") String message,
				@JsonProperty("requestedSchema") Map<String, Object> requestedSchema,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.message = message;
			this.requestedSchema = requestedSchema != null
					? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(requestedSchema)) : null;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public ElicitRequest(String message, Map<String, Object> requestedSchema) {
			this(message, requestedSchema, null);
		}

		public String getMessage() {
			return message;
		}

		public Map<String, Object> getRequestedSchema() {
			return requestedSchema;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String message;

			private Map<String, Object> requestedSchema;

			private Map<String, Object> meta;

			public Builder message(String v) {
				this.message = v;
				return this;
			}

			public Builder requestedSchema(Map<String, Object> v) {
				this.requestedSchema = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public Builder progressToken(Object token) {
				if (this.meta == null)
					this.meta = new HashMap<String, Object>();
				this.meta.put("progressToken", token);
				return this;
			}

			public ElicitRequest build() {
				return new ElicitRequest(message, requestedSchema, meta);
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ElicitResult implements Result {

		@JsonProperty("action")
		private final Action action;

		@JsonProperty("content")
		private final Map<String, Object> content;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ElicitResult(@JsonProperty("action") Action action, @JsonProperty("content") Map<String, Object> content,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.action = action;
			this.content = content != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(content))
					: null;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public ElicitResult(Action action, Map<String, Object> content) {
			this(action, content, null);
		}

		public enum Action {

			@JsonProperty("accept")
			ACCEPT, @JsonProperty("decline")
			DECLINE, @JsonProperty("cancel")
			CANCEL

		}

		public Action getAction() {
			return action;
		}

		public Map<String, Object> getContent() {
			return content;
		}

		public Map<String, Object> meta() {
			return _meta;
		}
	
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ElicitResult that = (ElicitResult) o;
			return action == that.action &&
					Objects.equals(content, that.content) &&
					Objects.equals(_meta, that._meta);
		}
	
		@Override
		public int hashCode() {
			return Objects.hash(action, content, _meta);
		}
	
		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private Action action;

			private Map<String, Object> content;

			private Map<String, Object> meta;

			public Builder message(Action v) {
				this.action = v;
				return this;
			}

			public Builder content(Map<String, Object> v) {
				this.content = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public ElicitResult build() {
				return new ElicitResult(action, content, meta);
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PaginatedRequest implements Request {

		@JsonProperty("cursor")
		private final String cursor;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public PaginatedRequest(@JsonProperty("cursor") String cursor,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.cursor = cursor;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public PaginatedRequest(String cursor) {
			this(cursor, null);
		}

		public PaginatedRequest() {
			this(null);
		}

		public String getCursor() {
			return cursor;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PaginatedResult {

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonCreator
		public PaginatedResult(@JsonProperty("nextCursor") String nextCursor) {
			this.nextCursor = nextCursor;
		}

		public String getNextCursor() {
			return nextCursor;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ProgressNotification implements Notification {

		@JsonProperty("progressToken")
		private final Object progressToken;

		@JsonProperty("progress")
		private final Double progress;

		@JsonProperty("total")
		private final Double total;

		@JsonProperty("message")
		private final String message;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ProgressNotification(@JsonProperty("progressToken") Object progressToken,
				@JsonProperty("progress") Double progress, @JsonProperty("total") Double total,
				@JsonProperty("message") String message, @JsonProperty("_meta") Map<String, Object> meta) {
			this.progressToken = progressToken;
			this.progress = progress;
			this.total = total;
			this.message = message;
			this._meta = meta != null ? Collections.unmodifiableMap(new LinkedHashMap<String, Object>(meta)) : null;
		}

		public ProgressNotification(Object progressToken, double progress, Double total, String message) {
			this(progressToken, Double.valueOf(progress), total, message, null);
		}

		public Object getProgressToken() {
			return progressToken;
		}

		public Double getProgress() {
			return progress;
		}

		public Double getTotal() {
			return total;
		}

		public String getMessage() {
			return message;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourcesUpdatedNotification implements Notification {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ResourcesUpdatedNotification(@JsonProperty("uri") String uri,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this._meta = meta;
		}

		public ResourcesUpdatedNotification(String uri) {
			this(uri, null);
		}

		public String getUri() {
			return uri;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class LoggingMessageNotification implements Notification {

		@JsonProperty("level")
		private final LoggingLevel level;

		@JsonProperty("logger")
		private final String logger;

		@JsonProperty("data")
		private final String data;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public LoggingMessageNotification(@JsonProperty("level") LoggingLevel level,
				@JsonProperty("logger") String logger, @JsonProperty("data") String data,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.level = level;
			this.logger = logger;
			this.data = data;
			this._meta = meta;
		}

		public LoggingMessageNotification(LoggingLevel level, String logger, String data) {
			this(level, logger, data, null);
		}

		public LoggingLevel getLevel() {
			return level;
		}

		public String getLogger() {
			return logger;
		}

		public String getData() {
			return data;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private LoggingLevel level = LoggingLevel.INFO;

			private String logger = "server";

			private String data;

			private Map<String, Object> meta;

			public Builder level(LoggingLevel v) {
				this.level = v;
				return this;
			}

			public Builder logger(String v) {
				this.logger = v;
				return this;
			}

			public Builder data(String v) {
				this.data = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public LoggingMessageNotification build() {
				return new LoggingMessageNotification(level, logger, data, meta);
			}

		}

	}

	public enum LoggingLevel {

		@JsonProperty("debug")
		DEBUG(0), @JsonProperty("info")
		INFO(1), @JsonProperty("notice")
		NOTICE(2), @JsonProperty("warning")
		WARNING(3), @JsonProperty("error")
		ERROR(4), @JsonProperty("critical")
		CRITICAL(5), @JsonProperty("alert")
		ALERT(6), @JsonProperty("emergency")
		EMERGENCY(7);

		private final int level;

		LoggingLevel(int level) {
			this.level = level;
		}

		public int level() {
			return level;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class SetLevelRequest {

		@JsonProperty("level")
		private final LoggingLevel level;

		@JsonCreator
		public SetLevelRequest(@JsonProperty("level") LoggingLevel level) {
			this.level = level;
		}

		public LoggingLevel getLevel() {
			return level;
		}

	}

	public interface CompleteReference {

		String getType();

		String identifier();

		default String type() {
			return getType();
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class PromptReference implements McpSchema.CompleteReference, Identifier {

		public static final String TYPE = "ref/prompt";

		@JsonProperty("type")
		private final String type;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonCreator
		public PromptReference(@JsonProperty("type") String type, @JsonProperty("name") String name,
				@JsonProperty("title") String title) {
			this.type = type;
			this.name = name;
			this.title = title;
		}

		public PromptReference(String type, String name) {
			this(type, name, null);
		}

		public PromptReference(String name) {
			this(TYPE, name, null);
		}

		public String getType() {
			return type;
		}

		public String getName() {
			return name;
		}

		public String getTitle() {
			return title;
		}

		@Override
		public String identifier() {
			return name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String title() {
			return title;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!(obj instanceof PromptReference))
				return false;
			PromptReference that = (PromptReference) obj;
			return Objects.equals(identifier(), that.identifier()) && Objects.equals(getType(), that.getType());
		}

		@Override
		public int hashCode() {
			return Objects.hash(identifier(), getType());
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceReference implements McpSchema.CompleteReference {

		public static final String TYPE = "ref/resource";

		@JsonProperty("type")
		private final String type;

		@JsonProperty("uri")
		private final String uri;

		@JsonCreator
		public ResourceReference(@JsonProperty("type") String type, @JsonProperty("uri") String uri) {
			this.type = type;
			this.uri = uri;
		}

		public ResourceReference(String uri) {
			this(TYPE, uri);
		}

		public String getType() {
			return type;
		}

		public String getUri() {
			return uri;
		}

		@Override
		public String identifier() {
			return uri;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CompleteRequest implements Request {

		@JsonProperty("ref")
		private final McpSchema.CompleteReference ref;

		@JsonProperty("argument")
		private final CompleteArgument argument;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonProperty("context")
		private final CompleteContext context;

		@JsonCreator
		public CompleteRequest(@JsonProperty("ref") McpSchema.CompleteReference ref,
				@JsonProperty("argument") CompleteArgument argument, @JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("context") CompleteContext context) {
			this.ref = ref;
			this.argument = argument;
			this._meta = meta;
			this.context = context;
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument, Map<String, Object> meta) {
			this(ref, argument, meta, null);
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument, CompleteContext context) {
			this(ref, argument, null, context);
		}

		public CompleteRequest(McpSchema.CompleteReference ref, CompleteArgument argument) {
			this(ref, argument, null, null);
		}

		public McpSchema.CompleteReference getRef() {
			return ref;
		}

		public CompleteArgument getArgument() {
			return argument;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		public CompleteContext getContext() {
			return context;
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompleteArgument {

			@JsonProperty("name")
			private final String name;

			@JsonProperty("value")
			private final String value;

			@JsonCreator
			public CompleteArgument(@JsonProperty("name") String name, @JsonProperty("value") String value) {
				this.name = name;
				this.value = value;
			}

			public String getName() {
				return name;
			}

			public String getValue() {
				return value;
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompleteContext {

			@JsonProperty("arguments")
			private final Map<String, String> arguments;

			@JsonCreator
			public CompleteContext(@JsonProperty("arguments") Map<String, String> arguments) {
				this.arguments = arguments;
			}

			public Map<String, String> getArguments() {
				return arguments;
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class CompleteResult implements Result {

		@JsonProperty("completion")
		private final CompleteCompletion completion;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public CompleteResult(@JsonProperty("completion") CompleteCompletion completion,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.completion = completion;
			this._meta = meta;
		}

		public CompleteResult(CompleteCompletion completion) {
			this(completion, null);
		}

		public CompleteCompletion getCompletion() {
			return completion;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		@JsonInclude(JsonInclude.Include.ALWAYS)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public static final class CompleteCompletion {

			@JsonProperty("values")
			private final List<String> values;

			@JsonProperty("total")
			private final Integer total;

			@JsonProperty("hasMore")
			private final Boolean hasMore;

			@JsonCreator
			public CompleteCompletion(@JsonProperty("values") List<String> values, @JsonProperty("total") Integer total,
					@JsonProperty("hasMore") Boolean hasMore) {
				this.values = values;
				this.total = total;
				this.hasMore = hasMore;
			}

			public List<String> getValues() {
				return values;
			}

			public Integer getTotal() {
				return total;
			}

			public Boolean getHasMore() {
				return hasMore;
			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = "text"),
			@JsonSubTypes.Type(value = ImageContent.class, name = "image"),
			@JsonSubTypes.Type(value = AudioContent.class, name = "audio"),
			@JsonSubTypes.Type(value = EmbeddedResource.class, name = "resource"),
			@JsonSubTypes.Type(value = ResourceLink.class, name = "resource_link") })
	public interface Content extends Meta {

		default String type() {
			if (this instanceof TextContent)
				return "text";
			else if (this instanceof ImageContent)
				return "image";
			else if (this instanceof AudioContent)
				return "audio";
			else if (this instanceof EmbeddedResource)
				return "resource";
			else if (this instanceof ResourceLink)
				return "resource_link";
			throw new IllegalArgumentException("Unknown content type: " + this);
		}

		default String getType() {
			return type();
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TextContent implements Annotated, Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("text")
		private final String text;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public TextContent(@JsonProperty("annotations") Annotations annotations, @JsonProperty("text") String text,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.annotations = annotations;
			this.text = text;
			this._meta = meta;
		}

		public TextContent(Annotations annotations, String text) {
			this(annotations, text, null);
		}

		public TextContent(String content) {
			this(null, content, null);
		}

		@Deprecated
		public TextContent(List<Role> audience, Double priority, String content) {
			this(audience != null && priority != null ? new Annotations(audience, priority) : null, content, null);
		}

		// ✅ OVERRIDE richiesto da Annotated
		@Override
		public Annotations annotations() {
			return annotations;
		}

		public Annotations getAnnotations() {
			return annotations;
		}

		public String getText() {
			return text;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		@Override
		public String type() {
			return "text";
		}

		@Override
		public String getType() {
			return type();
		}

		@Deprecated
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}
	
		@Deprecated
		public Double priority() {
			return annotations == null ? null : annotations.priority();
		}
	
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			TextContent that = (TextContent) o;
			return Objects.equals(annotations, that.annotations) &&
					Objects.equals(text, that.text) &&
					Objects.equals(_meta, that._meta);
		}
	
		@Override
		public int hashCode() {
			return Objects.hash(annotations, text, _meta);
		}
	
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ImageContent implements Annotated, Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("data")
		private final String data;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ImageContent(@JsonProperty("annotations") Annotations annotations, @JsonProperty("data") String data,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("_meta") Map<String, Object> meta) {
			this.annotations = annotations;
			this.data = data;
			this.mimeType = mimeType;
			this._meta = meta;
		}

		public ImageContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
		}

		@Deprecated
		public ImageContent(List<Role> audience, Double priority, String data, String mimeType) {
			this(audience != null && priority != null ? new Annotations(audience, priority) : null, data, mimeType,
					null);
		}

		// ✅ OVERRIDE richiesto da Annotated
		@Override
		public Annotations annotations() {
			return annotations;
		}

		public Annotations getAnnotations() {
			return annotations;
		}

		public String getData() {
			return data;
		}

		public String getMimeType() {
			return mimeType;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		@Override
		public String type() {
			return "image";
		}

		@Override
		public String getType() {
			return type();
		}

		@Deprecated
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}
	
		@Deprecated
		public Double priority() {
			return annotations == null ? null : annotations.priority();
		}
	
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ImageContent that = (ImageContent) o;
			return Objects.equals(annotations, that.annotations) &&
					Objects.equals(data, that.data) &&
					Objects.equals(mimeType, that.mimeType) &&
					Objects.equals(_meta, that._meta);
		}
	
		@Override
		public int hashCode() {
			return Objects.hash(annotations, data, mimeType, _meta);
		}
	
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class AudioContent implements Annotated, Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("data")
		private final String data;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public AudioContent(@JsonProperty("annotations") Annotations annotations, @JsonProperty("data") String data,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("_meta") Map<String, Object> meta) {
			this.annotations = annotations;
			this.data = data;
			this.mimeType = mimeType;
			this._meta = meta;
		}

		public AudioContent(Annotations annotations, String data, String mimeType) {
			this(annotations, data, mimeType, null);
		}

		// ✅ OVERRIDE richiesto da Annotated
		@Override
		public Annotations annotations() {
			return annotations;
		}

		public Annotations getAnnotations() {
			return annotations;
		}

		public String getData() {
			return data;
		}

		public String getMimeType() {
			return mimeType;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		@Override
		public String type() {
			return "audio";
		}
	
		@Override
		public String getType() {
			return type();
		}
	
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			AudioContent that = (AudioContent) o;
			return Objects.equals(annotations, that.annotations) &&
					Objects.equals(data, that.data) &&
					Objects.equals(mimeType, that.mimeType) &&
					Objects.equals(_meta, that._meta);
		}
	
		@Override
		public int hashCode() {
			return Objects.hash(annotations, data, mimeType, _meta);
		}
	
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class EmbeddedResource implements Annotated, Content {

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("resource")
		private final ResourceContents resource;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public EmbeddedResource(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("resource") ResourceContents resource, @JsonProperty("_meta") Map<String, Object> meta) {
			this.annotations = annotations;
			this.resource = resource;
			this._meta = meta;
		}

		public EmbeddedResource(Annotations annotations, ResourceContents resource) {
			this(annotations, resource, null);
		}

		@Deprecated
		public EmbeddedResource(List<Role> audience, Double priority, ResourceContents resource) {
			this(audience != null && priority != null ? new Annotations(audience, priority) : null, resource, null);
		}

		// ✅ OVERRIDE richiesto da Annotated
		@Override
		public Annotations annotations() {
			return annotations;
		}

		public Annotations getAnnotations() {
			return annotations;
		}

		public ResourceContents getResource() {
			return resource;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

		@Override
		public String type() {
			return "resource";
		}

		@Override
		public String getType() {
			return type();
		}

		@Deprecated
		public List<Role> audience() {
			return annotations == null ? null : annotations.audience();
		}
	
		@Deprecated
		public Double priority() {
			return annotations == null ? null : annotations.priority();
		}
	
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			EmbeddedResource that = (EmbeddedResource) o;
			return Objects.equals(annotations, that.annotations) &&
					Objects.equals(resource, that.resource) &&
					Objects.equals(_meta, that._meta);
		}
	
		@Override
		public int hashCode() {
			return Objects.hash(annotations, resource, _meta);
		}
	
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ResourceLink implements Content, ResourceContent {

		@JsonProperty("name")
		private final String name;

		@JsonProperty("title")
		private final String title;

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("description")
		private final String description;

		@JsonProperty("mimeType")
		private final String mimeType;

		@JsonProperty("size")
		private final Long size;

		@JsonProperty("annotations")
		private final Annotations annotations;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ResourceLink(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("uri") String uri, @JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size,
				@JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			this.name = name;
			this.title = title;
			this.uri = uri;
			this.description = description;
			this.mimeType = mimeType;
			this.size = size;
			this.annotations = annotations;
			this._meta = meta;
		}

		@Override
		public String type() {
			return "resource_link";
		}

		@Override
		public String getType() {
			return type();
		}

		public String getName() {
			return name;
		}

		public String getTitle() {
			return title;
		}

		public String getUri() {
			return uri;
		}

		public String getDescription() {
			return description;
		}

		public String getMimeType() {
			return mimeType;
		}

		public Long getSize() {
			return size;
		}

		public Annotations getAnnotations() {
			return annotations;
		}

		public Map<String, Object> meta() {
			return _meta;
		}
	
		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			ResourceLink that = (ResourceLink) o;
			return Objects.equals(name, that.name) &&
					Objects.equals(title, that.title) &&
					Objects.equals(uri, that.uri) &&
					Objects.equals(description, that.description) &&
					Objects.equals(mimeType, that.mimeType) &&
					Objects.equals(size, that.size) &&
					Objects.equals(annotations, that.annotations) &&
					Objects.equals(_meta, that._meta);
		}
	
		@Override
		public int hashCode() {
			return Objects.hash(name, title, uri, description, mimeType, size, annotations, _meta);
		}
	
		public static Builder builder() {
			return new Builder();
		}

		public static class Builder {

			private String name, title, uri, description, mimeType;

			private Long size;

			private Annotations annotations;

			private Map<String, Object> meta;

			public Builder name(String v) {
				this.name = v;
				return this;
			}

			public Builder title(String v) {
				this.title = v;
				return this;
			}

			public Builder uri(String v) {
				this.uri = v;
				return this;
			}

			public Builder description(String v) {
				this.description = v;
				return this;
			}

			public Builder mimeType(String v) {
				this.mimeType = v;
				return this;
			}

			public Builder size(Long v) {
				this.size = v;
				return this;
			}

			public Builder annotations(Annotations v) {
				this.annotations = v;
				return this;
			}

			public Builder meta(Map<String, Object> v) {
				this.meta = v;
				return this;
			}

			public ResourceLink build() {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");
				return new ResourceLink(name, title, uri, description, mimeType, size, annotations, meta);
			}

		}

		public String name() {
			return name;
		}

		public String title() {
			return title;
		}

		public String uri() {
			return uri;
		}

		public String description() {
			return description;
		}

		public String mimeType() {
			return mimeType;
		}

		public Long size() {
			return size;
		}

		public Annotations annotations() {
			return annotations;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class Root {

		@JsonProperty("uri")
		private final String uri;

		@JsonProperty("name")
		private final String name;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public Root(@JsonProperty("uri") String uri, @JsonProperty("name") String name,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.uri = uri;
			this.name = name;
			this._meta = meta;
		}

		public Root(String uri, String name) {
			this(uri, name, null);
		}

		public String getUri() {
			return uri;
		}

		public String getName() {
			return name;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class ListRootsResult implements Result {

		@JsonProperty("roots")
		private final List<Root> roots;

		@JsonProperty("nextCursor")
		private final String nextCursor;

		@JsonProperty("_meta")
		private final Map<String, Object> _meta;

		@JsonCreator
		public ListRootsResult(@JsonProperty("roots") List<Root> roots, @JsonProperty("nextCursor") String nextCursor,
				@JsonProperty("_meta") Map<String, Object> meta) {
			this.roots = roots != null ? Collections.unmodifiableList(new ArrayList<Root>(roots))
					: Collections.unmodifiableList(new ArrayList<Root>());
			this.nextCursor = nextCursor;
			this._meta = meta;
		}

		public ListRootsResult(List<Root> roots) {
			this(roots, null);
		}

		public ListRootsResult(List<Root> roots, String nextCursor) {
			this(roots, nextCursor, null);
		}

		public List<Root> getRoots() {
			return roots;
		}

		public String getNextCursor() {
			return nextCursor;
		}

		public Map<String, Object> meta() {
			return _meta;
		}

	}

}
