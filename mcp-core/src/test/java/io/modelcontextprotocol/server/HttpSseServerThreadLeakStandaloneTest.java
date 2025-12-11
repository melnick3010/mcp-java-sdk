/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standalone test per verificare thread leak nel server SSE.
 * 
 * Questo test:
 * 1. Avvia un server MCP SSE in Tomcat embedded
 * 2. Simula connessioni SSE multiple da client
 * 3. Verifica che i thread vengano correttamente rilasciati
 * 
 * Per eseguire questo test:
 * - Esegui: mvn test -Dtest=HttpSseServerThreadLeakStandaloneTest
 */
@Timeout(120)
public class HttpSseServerThreadLeakStandaloneTest {

	private static final Logger logger = LoggerFactory.getLogger(HttpSseServerThreadLeakStandaloneTest.class);

	@Test
	void testServerThreadLeakWithMultipleConnections() throws Exception {
		logger.info("\n\n=== TEST: Multiple SSE Connections - Server Thread Leak Check ===\n");

		ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
		
		// Baseline: conta i thread prima del test
		int baselineThreadCount = threadMXBean.getThreadCount();
		List<String> baselineThreadNames = getThreadNames(threadMXBean);
		logger.info("BASELINE: Total threads = {}", baselineThreadCount);
		logger.info("BASELINE: Reactor threads = {}", countReactorThreads(baselineThreadNames));
		logger.info("BASELINE: HTTP-SSE threads = {}", countHttpSseThreads(baselineThreadNames));

		// Avvia Tomcat con il server MCP
		int port = TomcatTestUtil.findAvailablePort();
		Tomcat tomcat = startTomcatWithMcpServer(port);
		String serverUrl = "http://localhost:" + port;
		
		try {
			Thread.sleep(2000); // Attendi che Tomcat sia completamente avviato
			
			int afterStartupThreadCount = threadMXBean.getThreadCount();
			List<String> afterStartupThreadNames = getThreadNames(threadMXBean);
			logger.info("AFTER STARTUP: Total threads = {}, HTTP-SSE threads = {}", 
					afterStartupThreadCount, countHttpSseThreads(afterStartupThreadNames));

			// Esegui 5 cicli di connessione/disconnessione SSE
			int iterations = 5;
			for (int i = 1; i <= iterations; i++) {
				logger.info("\n--- Iteration {}/{} ---", i, iterations);
				
				// Simula una connessione SSE
				simulateSseConnection(serverUrl);
				
				// Attendi un po' per permettere la pulizia
				Thread.sleep(1000);
				
				// Conta i thread dopo questa iterazione
				int currentThreadCount = threadMXBean.getThreadCount();
				List<String> currentThreadNames = getThreadNames(threadMXBean);
				int reactorThreads = countReactorThreads(currentThreadNames);
				int httpSseThreads = countHttpSseThreads(currentThreadNames);
				
				logger.info("After iteration {}: Total threads = {}, Reactor = {}, HTTP-SSE = {}", 
						i, currentThreadCount, reactorThreads, httpSseThreads);
				
				// Log dei thread sospetti
				if (httpSseThreads > 0) {
					logger.warn("Found {} HTTP-SSE threads still alive:", httpSseThreads);
					currentThreadNames.stream()
							.filter(name -> name.contains("http-sse-server"))
							.forEach(name -> logger.warn("  - {}", name));
				}
			}

			// Forza garbage collection
			logger.info("\nForcing garbage collection...");
			System.gc();
			Thread.sleep(2000);

			// Verifica finale
			int finalThreadCount = threadMXBean.getThreadCount();
			List<String> finalThreadNames = getThreadNames(threadMXBean);
			int finalReactorThreads = countReactorThreads(finalThreadNames);
			int finalHttpSseThreads = countHttpSseThreads(finalThreadNames);
			
			logger.info("\nFINAL: Total threads = {}", finalThreadCount);
			logger.info("FINAL: Reactor threads = {}", finalReactorThreads);
			logger.info("FINAL: HTTP-SSE threads = {}", finalHttpSseThreads);
			
			// Calcola l'incremento rispetto al baseline (escludendo i thread di Tomcat)
			int threadIncrease = finalThreadCount - afterStartupThreadCount;
			logger.info("\nThread increase from after startup: {}", threadIncrease);

			// Log dettagliato dei thread finali
			if (finalHttpSseThreads > 0) {
				logger.error("\n=== THREAD LEAK DETECTED ===");
				logger.error("Found {} HTTP-SSE threads still alive after all connections closed:", finalHttpSseThreads);
				finalThreadNames.stream()
						.filter(name -> name.contains("http-sse-server"))
						.forEach(name -> logger.error("  - {}", name));
			}

			// Assertions
			assertThat(finalHttpSseThreads)
					.as("No HTTP-SSE server threads should remain after closing all connections")
					.isEqualTo(0);
			
			assertThat(threadIncrease)
					.as("Thread count should not increase significantly (max 10 threads tolerance for Tomcat)")
					.isLessThanOrEqualTo(10);
			
			logger.info("\n=== TEST PASSED: No thread leak detected ===\n");
			
		} finally {
			stopTomcat(tomcat);
		}
	}

	@Test
	void testSingleSseConnectionLifecycle() throws Exception {
		logger.info("\n\n=== TEST: Single SSE Connection Lifecycle ===\n");

		ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
		
		// Avvia Tomcat
		int port = TomcatTestUtil.findAvailablePort();
		Tomcat tomcat = startTomcatWithMcpServer(port);
		String serverUrl = "http://localhost:" + port;
		
		try {
			Thread.sleep(2000);
			
			int beforeThreadCount = threadMXBean.getThreadCount();
			List<String> beforeThreadNames = getThreadNames(threadMXBean);
			logger.info("BEFORE CONNECTION: Total threads = {}, HTTP-SSE threads = {}", 
					beforeThreadCount, countHttpSseThreads(beforeThreadNames));

			// Apri connessione SSE
			logger.info("Opening SSE connection...");
			CountDownLatch connectionClosed = new CountDownLatch(1);
			Thread sseThread = new Thread(() -> {
				try {
					URL url = new URL(serverUrl + "/sse");
					HttpURLConnection conn = (HttpURLConnection) url.openConnection();
					conn.setRequestProperty("Accept", "text/event-stream");
					conn.setRequestProperty("Cache-Control", "no-cache");
					conn.setRequestProperty("MCP-Protocol-Version", "2024-11-05");
					
					try (BufferedReader reader = new BufferedReader(
							new InputStreamReader(conn.getInputStream()))) {
						String line;
						int linesRead = 0;
						while ((line = reader.readLine()) != null && linesRead < 5) {
							logger.info("SSE: {}", line);
							linesRead++;
						}
					}
				} catch (Exception e) {
					logger.debug("SSE connection closed: {}", e.getMessage());
				} finally {
					connectionClosed.countDown();
				}
			});
			sseThread.start();
			
			Thread.sleep(2000);
			int duringThreadCount = threadMXBean.getThreadCount();
			List<String> duringThreadNames = getThreadNames(threadMXBean);
			int duringHttpSseThreads = countHttpSseThreads(duringThreadNames);
			logger.info("DURING CONNECTION: Total threads = {}, HTTP-SSE threads = {}", 
					duringThreadCount, duringHttpSseThreads);

			// Chiudi connessione
			logger.info("Closing SSE connection...");
			sseThread.interrupt();
			connectionClosed.await(5, TimeUnit.SECONDS);
			
			// Attendi pulizia
			Thread.sleep(2000);
			System.gc();
			Thread.sleep(1000);
			
			int afterThreadCount = threadMXBean.getThreadCount();
			List<String> afterThreadNames = getThreadNames(threadMXBean);
			int afterHttpSseThreads = countHttpSseThreads(afterThreadNames);
			logger.info("AFTER CONNECTION: Total threads = {}, HTTP-SSE threads = {}", 
					afterThreadCount, afterHttpSseThreads);

			// Verifica che i thread siano stati rilasciati
			assertThat(afterHttpSseThreads)
					.as("HTTP-SSE threads should be disposed after connection close")
					.isEqualTo(0);
			
			logger.info("\n=== TEST PASSED ===\n");
			
		} finally {
			stopTomcat(tomcat);
		}
	}

	private Tomcat startTomcatWithMcpServer(int port) throws LifecycleException {
		logger.info("Starting Tomcat on port {}...", port);
		
		HttpServletSseServerTransportProvider transport = HttpServletSseServerTransportProvider.builder().build();
		
		McpSyncServer mcpServer = McpServer.sync(transport)
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(true)
						.prompts(true)
						.resources(true, true)
						.build())
				.tools(new McpServerFeatures.SyncToolSpecification(
						McpSchema.Tool.builder()
								.name("test-tool")
								.description("A test tool")
								.build(),
						null,
						(exchange, request) -> new McpSchema.CallToolResult(
								Collections.singletonList(new McpSchema.TextContent("Test result")),
								false)))
				.build();

		Tomcat tomcat = TomcatTestUtil.createTomcatServer("", port, transport);
		tomcat.start();
		
		logger.info("Tomcat started successfully");
		return tomcat;
	}

	private void stopTomcat(Tomcat tomcat) {
		if (tomcat != null && tomcat.getServer().getState() == LifecycleState.STARTED) {
			try {
				logger.info("Stopping Tomcat...");
				tomcat.stop();
				tomcat.destroy();
				logger.info("Tomcat stopped");
			} catch (Exception e) {
				logger.error("Error stopping Tomcat", e);
			}
		}
	}

	private void simulateSseConnection(String serverUrl) {
		logger.info("Simulating SSE connection to {}", serverUrl);
		
		Thread connectionThread = new Thread(() -> {
			try {
				URL url = new URL(serverUrl + "/sse");
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setRequestProperty("Accept", "text/event-stream");
				conn.setRequestProperty("Cache-Control", "no-cache");
				conn.setRequestProperty("MCP-Protocol-Version", "2024-11-05");
				conn.setConnectTimeout(5000);
				conn.setReadTimeout(5000);
				
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(conn.getInputStream()))) {
					String line;
					int linesRead = 0;
					// Leggi solo alcune righe poi chiudi
					while ((line = reader.readLine()) != null && linesRead < 3) {
						logger.debug("SSE received: {}", line);
						linesRead++;
					}
				}
				
				conn.disconnect();
				logger.info("SSE connection closed normally");
			} catch (Exception e) {
				logger.debug("SSE connection ended: {}", e.getMessage());
			}
		});
		
		connectionThread.start();
		
		try {
			// Attendi che la connessione si chiuda o timeout
			connectionThread.join(3000);
			if (connectionThread.isAlive()) {
				connectionThread.interrupt();
				connectionThread.join(1000);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private List<String> getThreadNames(ThreadMXBean threadMXBean) {
		ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(false, false);
		return Arrays.stream(threadInfos)
				.map(ThreadInfo::getThreadName)
				.collect(Collectors.toList());
	}

	private int countReactorThreads(List<String> threadNames) {
		return (int) threadNames.stream()
				.filter(name -> name.contains("reactor-") || name.contains("parallel-") || name.contains("elastic-"))
				.count();
	}

	private int countHttpSseThreads(List<String> threadNames) {
		return (int) threadNames.stream()
				.filter(name -> name.contains("http-sse-server"))
				.count();
	}
}

// Made with Bob
