package io.modelcontextprotocol.logging;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.MDC;

import io.modelcontextprotocol.json.McpJsonMapper;

/**
 * Lightweight helper to emit structured MCP logs and manage MDC fields.
 */
public final class McpLogging {

	private static final McpJsonMapper MAPPER = McpJsonMapper.getDefault();

	private McpLogging() {
	}

	public static void put(String key, String value) {
		if (key != null && value != null) {
			MDC.put(key, value);
		}
	}

	public static void remove(String key) {
		if (key != null) {
			MDC.remove(key);
		}
	}

	public static void clear() {
		MDC.clear();
	}

	public static void logEvent(Logger logger, String side, String channel, String event, String sessionId,
			Map<String, Object> jsonrpc, Map<String, Object> corr, Map<String, Object> outcome,
			Map<String, Object> extra) {
		try {
			Map<String, Object> o = new HashMap<>();
			o.put("side", side);
			o.put("channel", channel);
			o.put("event", event);
			o.put("ts", Instant.now().toString());
			o.put("thread", Thread.currentThread().getName());
			if (sessionId != null)
				o.put("sessionId", sessionId);
			if (jsonrpc != null)
				o.put("jsonrpc", jsonrpc);
			if (corr != null)
				o.put("corr", corr);
			if (outcome != null)
				o.put("outcome", outcome);
			if (extra != null)
				o.put("extra", extra);
			String json = MAPPER.writeValueAsString(o);
			logger.info(json);
		}
		catch (Exception e) {
			logger.warn("Failed to emit structured MCP log: {}", e.getMessage());
		}
	}

}
