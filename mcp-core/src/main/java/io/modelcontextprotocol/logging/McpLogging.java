package io.modelcontextprotocol.logging;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

	/**
	 * Emissione di un evento strutturato MCP.
	 * @param logger SLF4J logger
	 * @param side "CLIENT" | "SERVER"
	 * @param channel "SSE" | "HTTP" | "ASYNC" | "TEST" ...
	 * @param event nome evento (es. "S_SSE_ENDPOINT")
	 * @param sessionId sessionId (opzionale)
	 * @param jsonrpc mappa con {method, kind, id} (opzionale)
	 * @param corr mappa con correlazione {initiatorId, parentId, seq, pending}
	 * (opzionale)
	 * @param outcome mappa con outcome {status, cause, ...} (opzionale)
	 * @param extra mappa con dati accessori; se contiene "meta", viene portata al
	 * top-level "meta"
	 */
	public static void logEvent(Logger logger, String side, String channel, String event, String sessionId,
			Map<String, Object> jsonrpc, Map<String, Object> corr, Map<String, Object> outcome,
			Map<String, Object> extra) {
		try {
			// Usa LinkedHashMap per preservare l'ordine dei campi
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("side", side);
			o.put("channel", channel);
			o.put("event", event);
			o.put("ts", Instant.now().toString());
			o.put("thread", Thread.currentThread().getName());

			if (sessionId != null) {
				o.put("sessionId", sessionId);
			}
			if (jsonrpc != null) {
				o.put("jsonrpc", jsonrpc);
			}
			if (corr != null) {
				o.put("corr", corr);
			}
			if (outcome != null) {
				o.put("outcome", outcome);
			}

			// --- Normalizzazione 'extra' → 'meta' top-level ---
			if (extra != null && !extra.isEmpty()) {
				Object maybeMeta = extra.get("meta");
				Map<String, Object> extraWithoutMeta = new LinkedHashMap<>();
				for (Map.Entry<String, Object> e : extra.entrySet()) {
					if (!"meta".equals(e.getKey())) {
						extraWithoutMeta.put(e.getKey(), e.getValue());
					}
				}

				// Se 'extra' contiene 'meta', portalo al top-level
				if (maybeMeta instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> meta = (Map<String, Object>) maybeMeta;
					o.put("meta", meta);
				}

				// Se restano altre chiavi in 'extra', mantienile
				if (!extraWithoutMeta.isEmpty()) {
					o.put("extra", extraWithoutMeta);
				}
			}

			String json = MAPPER.writeValueAsString(o);
			logger.info(json);
		}
		catch (Exception e) {
			logger.warn("Failed to emit structured MCP log: {}", e.getMessage());
		}
	}

}
