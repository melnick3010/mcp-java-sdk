package io.modelcontextprotocol.logging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Basic validator that scans surefire report txt files for structured MCP JSON lines and
 * performs lightweight invariants checks. This test is skipped when no structured logs
 * are present.
 */
public class McpLogInvariantsTest {

	private static final ObjectMapper M = new ObjectMapper();

	@Test
	public void basicOrphanSseSendCheck() throws IOException {
		Path dir = java.nio.file.Paths.get("target", "surefire-reports");
		Assumptions.assumeTrue(Files.exists(dir), "No surefire reports; skipping log audit");

		List<JsonNode> events = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.filter(p -> p.getFileName().toString().endsWith(".txt")).forEach(p -> {
				try {
					Files.readAllLines(p).forEach(line -> {
						try {
							JsonNode n = M.readTree(line);
							if (n.has("event"))
								events.add(n);
						}
						catch (Exception e) {
							// ignore non-json lines
						}
					});
				}
				catch (IOException e) {
					// ignore
				}
			});
		}

		boolean anySseSend = events.stream().anyMatch(n -> "S_SSE_SEND".equals(n.path("event").asText(null)));
		// If no S_SSE_SEND present, nothing to assert
		Assumptions.assumeTrue(anySseSend, "No S_SSE_SEND events found; skipping specific checks");

		// Basic check: every S_SSE_SEND with kind=RESPONSE should have a client C_SSE_RAW
		// and C_RECV_RESP_COMPLETE (receipt and terminal delivery)
		boolean violationFound = events.stream().filter(n -> "S_SSE_SEND".equals(n.path("event").asText(null)))
				.filter(n -> "RESPONSE".equals(n.path("jsonrpc").path("kind").asText(null))).anyMatch(n -> {
					String id = n.path("jsonrpc").path("id").asText(null);
					boolean raw = events.stream().anyMatch(e -> "C_SSE_RAW".equals(e.path("event").asText(null))
							&& id != null && id.equals(e.path("jsonrpc").path("id").asText(null)));
					boolean complete = events.stream()
							.anyMatch(e -> "C_RECV_RESP_COMPLETE".equals(e.path("event").asText(null)) && id != null
									&& id.equals(e.path("jsonrpc").path("id").asText(null)));
					// require both receipt (raw) and a terminal completion
					return !(raw && complete);
				});

		assertTrue(!violationFound, "Found orphan S_SSE_SEND RESPONSE events (see surefire logs)");

		// Terminal event check: every jsonrpc id should have exactly one terminal event
		// (either C_RECV_RESP_COMPLETE or S_REQ_COMPLETED)
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (com.fasterxml.jackson.databind.JsonNode n : events) {
			if (n.has("jsonrpc") && n.path("jsonrpc").has("id")) {
				ids.add(n.path("jsonrpc").path("id").asText());
			}
		}
		boolean terminalViolation = ids.stream().anyMatch(id -> {
			long count = events.stream().filter(e -> {
				String ev = e.path("event").asText(null);
				if (!"C_RECV_RESP_COMPLETE".equals(ev) && !"S_REQ_COMPLETED".equals(ev))
					return false;
				return id.equals(e.path("jsonrpc").path("id").asText(null));
			}).count();
			return count != 1;
		});
		assertTrue(!terminalViolation,
				"Found ids without exactly one terminal event (C_RECV_RESP_COMPLETE or S_REQ_COMPLETED)");

		// Correlation parent/initiator id checks: referenced ids must exist
		boolean corrViolation = events.stream().filter(n -> n.has("corr") && n.path("corr").isObject()).anyMatch(n -> {
			com.fasterxml.jackson.databind.JsonNode corr = n.path("corr");
			for (String key : new String[] { "initiatorId", "parentId" }) {
				if (corr.has(key)) {
					String ref = corr.path(key).asText(null);
					if (ref != null && !ids.contains(ref))
						return true;
				}
			}
			return false;
		});
		assertTrue(!corrViolation, "Found corr references to unknown ids");
	}

}
