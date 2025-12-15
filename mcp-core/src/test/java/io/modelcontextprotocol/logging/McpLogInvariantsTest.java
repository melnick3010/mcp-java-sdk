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
		// or C_RECV_RESP_COMPLETE
		boolean violationFound = events.stream().filter(n -> "S_SSE_SEND".equals(n.path("event").asText(null)))
				.filter(n -> "RESPONSE".equals(n.path("jsonrpc").path("kind").asText(null))).anyMatch(n -> {
					String id = n.path("jsonrpc").path("id").asText(null);
					boolean raw = events.stream().anyMatch(e -> "C_SSE_RAW".equals(e.path("event").asText(null))
							&& id != null && id.equals(e.path("jsonrpc").path("id").asText(null)));
					boolean complete = events.stream()
							.anyMatch(e -> "C_RECV_RESP_COMPLETE".equals(e.path("event").asText(null)) && id != null
									&& id.equals(e.path("jsonrpc").path("id").asText(null)));
					return !(raw || complete);
				});

		assertTrue(!violationFound, "Found orphan S_SSE_SEND RESPONSE events (see surefire logs)");
	}

}
