/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Standalone test per verificare thread leak nel client SSE.
 * 
 * Questo test:
 * 1. Avvia un server MCP SSE in un container Docker
 * 2. Crea e chiude ripetutamente client SSE
 * 3. Verifica che i thread vengano correttamente rilasciati
 * 
 * Per eseguire questo test:
 * - Assicurati che Docker sia in esecuzione
 * - Esegui: mvn test -Dtest=HttpSseClientThreadLeakStandaloneTest
 */
@Timeout(120)
public class HttpSseClientThreadLeakStandaloneTest {

	private static final Logger logger = LoggerFactory.getLogger(HttpSseClientThreadLeakStandaloneTest.class);

	private static GenericContainer<?> serverContainer;
	private static String serverUrl;

	@BeforeAll
	static void startServer() {
		logger.info("=== Starting MCP SSE Server Container ===");
		
		// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
		serverContainer = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
				.withCommand("node dist/index.js sse")
				.withLogConsumer(outputFrame -> System.out.println("[SERVER] " + outputFrame.getUtf8String()))
				.withExposedPorts(3001)
				.waitingFor(Wait.forHttp("/").forStatusCode(404));

		serverContainer.start();
		
		String host = serverContainer.getHost();
		Integer port = serverContainer.getMappedPort(3001);
		serverUrl = "http://" + host + ":" + port;
		
		logger.info("=== Server started at: {} ===", serverUrl);
	}

	@AfterAll
	static void stopServer() {
		if (serverContainer != null) {
			logger.info("=== Stopping server container ===");
			serverContainer.stop();
		}
	}

	@Test
	void testClientThreadLeakWithMultipleConnections() throws InterruptedException {
		logger.info("\n\n=== TEST: Multiple Client Connections - Thread Leak Check ===\n");

		ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
		
		// Baseline: conta i thread prima del test
		int baselineThreadCount = threadMXBean.getThreadCount();
		List<String> baselineThreadNames = getThreadNames(threadMXBean);
		logger.info("BASELINE: Total threads = {}", baselineThreadCount);
		logger.info("BASELINE: Reactor threads = {}", countReactorThreads(baselineThreadNames));
		logger.info("BASELINE: HTTP-SSE threads = {}", countHttpSseThreads(baselineThreadNames));

		// Esegui 5 cicli di connessione/disconnessione
		int iterations = 5;
		for (int i = 1; i <= iterations; i++) {
			logger.info("\n--- Iteration {}/{} ---", i, iterations);
			
			// Crea e usa il client
			McpAsyncClient client = createAndInitializeClient();
			
			// Esegui una semplice operazione
			List<McpSchema.Tool> tools = client.listTools(null).block(Duration.ofSeconds(5));
			logger.info("Retrieved {} tools", tools != null ? tools.size() : 0);
			
			// Chiudi il client
			logger.info("Closing client...");
			client.closeGracefully().block(Duration.ofSeconds(5));
			
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
						.filter(name -> name.contains("http-sse-client"))
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
		
		// Calcola l'incremento rispetto al baseline
		int threadIncrease = finalThreadCount - baselineThreadCount;
		logger.info("\nThread increase from baseline: {}", threadIncrease);

		// Log dettagliato dei thread finali
		if (finalHttpSseThreads > 0) {
			logger.error("\n=== THREAD LEAK DETECTED ===");
			logger.error("Found {} HTTP-SSE threads still alive after all clients closed:", finalHttpSseThreads);
			finalThreadNames.stream()
					.filter(name -> name.contains("http-sse-client"))
					.forEach(name -> logger.error("  - {}", name));
		}

		// Assertions
		assertThat(finalHttpSseThreads)
				.as("No HTTP-SSE client threads should remain after closing all clients")
				.isEqualTo(0);
		
		assertThat(threadIncrease)
				.as("Thread count should not increase significantly (max 5 threads tolerance)")
				.isLessThanOrEqualTo(5);
		
		logger.info("\n=== TEST PASSED: No thread leak detected ===\n");
	}

	@Test
	void testSingleClientLifecycle() throws InterruptedException {
		logger.info("\n\n=== TEST: Single Client Lifecycle ===\n");

		ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
		
		int beforeThreadCount = threadMXBean.getThreadCount();
		List<String> beforeThreadNames = getThreadNames(threadMXBean);
		logger.info("BEFORE: Total threads = {}, HTTP-SSE threads = {}", 
				beforeThreadCount, countHttpSseThreads(beforeThreadNames));

		// Crea client
		logger.info("Creating client...");
		McpAsyncClient client = createAndInitializeClient();
		
		Thread.sleep(500);
		int duringThreadCount = threadMXBean.getThreadCount();
		List<String> duringThreadNames = getThreadNames(threadMXBean);
		int duringHttpSseThreads = countHttpSseThreads(duringThreadNames);
		logger.info("DURING: Total threads = {}, HTTP-SSE threads = {}", 
				duringThreadCount, duringHttpSseThreads);
		
		// Verifica che i thread siano stati creati
		assertThat(duringHttpSseThreads)
				.as("HTTP-SSE threads should be created when client is active")
				.isGreaterThan(0);

		// Chiudi client
		logger.info("Closing client...");
		client.closeGracefully().block(Duration.ofSeconds(5));
		
		// Attendi pulizia
		Thread.sleep(2000);
		System.gc();
		Thread.sleep(1000);
		
		int afterThreadCount = threadMXBean.getThreadCount();
		List<String> afterThreadNames = getThreadNames(threadMXBean);
		int afterHttpSseThreads = countHttpSseThreads(afterThreadNames);
		logger.info("AFTER: Total threads = {}, HTTP-SSE threads = {}", 
				afterThreadCount, afterHttpSseThreads);

		// Verifica che i thread siano stati rilasciati
		assertThat(afterHttpSseThreads)
				.as("HTTP-SSE threads should be disposed after client close")
				.isEqualTo(0);
		
		logger.info("\n=== TEST PASSED ===\n");
	}

	private McpAsyncClient createAndInitializeClient() {
		AtomicReference<McpAsyncClient> clientRef = new AtomicReference<>();

		assertThatCode(() -> {
			HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(serverUrl).build();
			
			McpAsyncClient client = McpClient.async(transport)
					.capabilities(McpSchema.ClientCapabilities.builder().build())
					.build();

			client.initialize().block(Duration.ofSeconds(10));
			clientRef.set(client);
		}).doesNotThrowAnyException();

		return clientRef.get();
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
				.filter(name -> name.contains("http-sse-client"))
				.count();
	}
}

// Made with Bob
