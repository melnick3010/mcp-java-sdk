/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.util.Utils;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.util.UriComponentsBuilder;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link HttpClientSseClientTransport} class.
 *
 * @author Christian Tzolov
 */
@Timeout(15)
class HttpClientSseClientTransportTests {

	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v3")
		.withCommand("node dist/index.js sse")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private TestHttpClientSseClientTransport transport;

	private final McpTransportContext context = McpTransportContext
		.create(Collections.singletonMap("some-key", "some-value"));

	// Test class to access protected methods

	// Test class to access protected methods
	static class TestHttpClientSseClientTransport extends HttpClientSseClientTransport {

		private final java.util.concurrent.atomic.AtomicInteger inboundMessageCount = new java.util.concurrent.atomic.AtomicInteger(
				0);

		private final reactor.core.publisher.Sinks.Many<org.springframework.http.codec.ServerSentEvent<String>> events = reactor.core.publisher.Sinks
			.many()
			.unicast()
			.onBackpressureBuffer();

		public TestHttpClientSseClientTransport(final String baseUri) {
			super(org.apache.http.impl.client.HttpClients.createDefault(), baseUri, "/sse", // oppure
																							// un
																							// endpoint
																							// passato
																							// via
																							// costruttore
																							// se
																							// preferisci
																							// parametrizzarlo
					io.modelcontextprotocol.json.McpJsonMapper.getDefault(),
					io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer.NOOP);
		}

		public int getInboundMessageCount() {
			return inboundMessageCount.get();
		}

		public void simulateEndpointEvent(String jsonMessage) {
			events.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder()
				.event("endpoint")
				.data(jsonMessage)
				.build());
			inboundMessageCount.incrementAndGet();
		}

		public void simulateMessageEvent(String jsonMessage) {
			events.tryEmitNext(org.springframework.http.codec.ServerSentEvent.<String>builder()
				.event("message")
				.data(jsonMessage)
				.build());
			inboundMessageCount.incrementAndGet();
		}

	}

	@BeforeAll
	static void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;

	}

	@AfterAll
	static void stopContainer() {
		container.stop();
	}

	@BeforeEach
	void setUp() {
		transport = new TestHttpClientSseClientTransport(host);
		transport.connect(Function.identity()).block();
	}

	@AfterEach
	void afterEach() {
		if (transport != null) {
			assertThatCode(() -> transport.closeGracefully().block(Duration.ofSeconds(10))).doesNotThrowAnyException();
		}
	}

	@Test
	void testErrorOnBogusMessage() {
		// bogus message
		JSONRPCRequest bogusMessage = new JSONRPCRequest(null, null, "test-id",
				Collections.singletonMap("key", "value"));

		StepVerifier.create(transport.sendMessage(bogusMessage))
			.verifyErrorMessage(
					"Sending message failed with a non-OK HTTP code: 400 - Invalid message: {\"id\":\"test-id\",\"params\":{\"key\":\"value\"}}");
	}

	@Test
	void testMessageProcessing() {
		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.singletonMap("key", "value"));

		// Simulate receiving the message
		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"method\": \"test-method\",\n"
				+ "    \"id\": \"test-id\",\n" + "    \"params\": {\"key\": \"value\"}\n" + "}");

		// Subscribe to messages and verify
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testResponseMessageProcessing() {
		// Simulate receiving a response message

		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"id\": \"test-id\",\n"
				+ "    \"result\": {\"status\": \"success\"}\n" + "}");

		// Create and send a request message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.singletonMap("key", "value"));

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testErrorMessageProcessing() {
		// Simulate receiving an error message

		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"id\": \"test-id\",\n"
				+ "    \"error\": {\n" + "        \"code\": -32600,\n" + "        \"message\": \"Invalid Request\"\n"
				+ "    }\n" + "}");

		// Create and send a request message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.singletonMap("key", "value"));

		// Verify message handling
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testNotificationMessageProcessing() {
		// Simulate receiving a notification message (no id)

		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"method\": \"update\",\n"
				+ "    \"params\": {\"status\": \"processing\"}\n" + "}");

		// Verify the notification was processed
		assertThat(transport.getInboundMessageCount()).isEqualTo(1);
	}

	@Test
	void testGracefulShutdown() {
		// Test graceful shutdown
		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		// Create a test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.singletonMap("key", "value"));

		// Verify message is not processed after shutdown
		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();

		// Message count should remain 0 after shutdown
		assertThat(transport.getInboundMessageCount()).isZero();
	}

	@Test
	void testRetryBehavior() {
		// Create a client that simulates connection failures
		HttpClientSseClientTransport failingTransport = HttpClientSseClientTransport.builder("http://non-existent-host")
			.build();

		// Verify that the transport attempts to reconnect
		StepVerifier.create(Mono.delay(Duration.ofSeconds(2))).expectNextCount(1).verifyComplete();

		// Clean up
		failingTransport.closeGracefully().block();
	}

	@Test
	void testMultipleMessageProcessing() {
		// Simulate receiving multiple messages in sequence

		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"method\": \"method1\",\n"
				+ "    \"id\": \"id1\",\n" + "    \"params\": {\"key\": \"value1\"}\n" + "}");

		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"method\": \"method2\",\n"
				+ "    \"id\": \"id2\",\n" + "    \"params\": {\"key\": \"value2\"}\n" + "}");

		// Create and send corresponding messages
		JSONRPCRequest message1 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method1", "id1",
				Collections.singletonMap("key", "value1"));

		JSONRPCRequest message2 = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method2", "id2",
				Collections.singletonMap("key", "value2"));

		// Verify both messages are processed
		StepVerifier.create(transport.sendMessage(message1).then(transport.sendMessage(message2))).verifyComplete();

		// Verify message count
		assertThat(transport.getInboundMessageCount()).isEqualTo(2);
	}

	@Test
	void testMessageOrderPreservation() {
		// Simulate receiving messages in a specific order

		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"method\": \"first\",\n"
				+ "    \"id\": \"1\",\n" + "    \"params\": {\"sequence\": 1}\n" + "}");

		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"method\": \"second\",\n"
				+ "    \"id\": \"2\",\n" + "    \"params\": {\"sequence\": 2}\n" + "}");

		transport.simulateMessageEvent("{\n" + "    \"jsonrpc\": \"2.0\",\n" + "    \"method\": \"third\",\n"
				+ "    \"id\": \"3\",\n" + "    \"params\": {\"sequence\": 3}\n" + "}");

		// Verify message count and order
		assertThat(transport.getInboundMessageCount()).isEqualTo(3);
	}

	@Test
	void testCustomizeClient() {
		AtomicBoolean customizerCalled = new AtomicBoolean(false);

		HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(host)
			.asyncHttpRequestCustomizer((builder, method, uri, body, ctx) -> {
				builder.addHeader("X-Test-Customized", "true");
				customizerCalled.set(true);
				return reactor.core.publisher.Mono.just(builder);
			})
			.build();

		// Triggera almeno una richiesta per far scattare il customizer:
		// 1) via connect() (GET) se l'SSE è disponibile, oppure
		// 2) simula messageEndpoint e invia una POST:
		// messageEndpoint.set("/messages"); <-- se hai visibilità in test
		// transport.sendMessage(dummyJsonRpcMessage()).block();

		assertThat(customizerCalled.get()).isTrue();
		transport.closeGracefully().block();
	}

	@Test
	void testCustomizeRequest() throws InterruptedException {
		// Flag per verificare l'invocazione del customizer
		AtomicBoolean customizerCalled = new AtomicBoolean(false);

		// Per verificare l'header impostato
		AtomicReference<String> headerName = new AtomicReference<>();
		AtomicReference<String> headerValue = new AtomicReference<>();

		// Costruisci un transport con il customizer asincrono
		HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
			.asyncHttpRequestCustomizer((builder, method, uri, body, context) -> {
				// 'builder' è org.apache.http.client.methods.RequestBuilder (Apache)
				builder.addHeader("X-Custom-Header", "test-value");
				customizerCalled.set(true);

				// Costruisci una richiesta di verifica dal builder e leggi l'header
				HttpUriRequest request = builder.setUri(URI.create("http://example.com")).build();
				headerName.set("X-Custom-Header");
				headerValue.set(request.getFirstHeader("X-Custom-Header") != null
						? request.getFirstHeader("X-Custom-Header").getValue() : null);

				// Il customizer deve restituire un Publisher<RequestBuilder> (Mono nel
				// nostro caso)
				return Mono.just(builder);
			})
			.build();

		// Triggera il customizer avviando almeno una richiesta.
		// Opzione A: avvia la connect() (GET SSE). Il customizer viene invocato PRIMA
		// dell'esecuzione HTTP.
		customizedTransport.connect(m -> Mono.empty()).subscribe();

		// Attendi brevemente per consentire al boundedElastic di eseguire la preparazione
		// della request
		Thread.sleep(100); // evita flakiness; se preferisci usa meccanismi di
							// sincronizzazione del tuo framework

		// Verifiche
		assertThat(customizerCalled.get()).isTrue();
		assertThat(headerName.get()).isEqualTo("X-Custom-Header");
		assertThat(headerValue.get()).isEqualTo("test-value");

		// Cleanup
		customizedTransport.closeGracefully().block();
	}

	@Test
	void testChainedCustomizations() throws InterruptedException {
		AtomicBoolean clientCustomizerCalled = new AtomicBoolean(false);
		AtomicBoolean requestCustomizerCalled = new AtomicBoolean(false);

		// Client-level: usa il factory per impostare connectTimeout (analogo al Java 17)
		HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(host).clientFactory(() -> {
			// timeout 30s (equivalente semantico della connectTimeout del JDK 11)
			RequestConfig rc = RequestConfig.custom()
				.setConnectTimeout(30_000) // ms
				.setSocketTimeout(30_000)
				.build();
			CloseableHttpClient client = HttpClientBuilder.create().setDefaultRequestConfig(rc).build();
			clientCustomizerCalled.set(true);
			return client;
		})
			// Request-level: aggiungi header
			.asyncHttpRequestCustomizer((builder, method, uri, body, ctx) -> {
				builder.addHeader("X-Api-Key", "test-api-key");
				requestCustomizerCalled.set(true);
				return Mono.just(builder);
			})
			.build();

		// Trigger: prepara una richiesta (GET connect o POST)
		transport.connect(m -> Mono.empty()).subscribe();
		Thread.sleep(100);

		assertThat(clientCustomizerCalled.get()).isTrue();
		assertThat(requestCustomizerCalled.get()).isTrue();

		transport.closeGracefully().block();
	}

	@Test
	void testRequestCustomizer()
			throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
		// 1) Mock del customizer sincrono (verificheremo chiamate e argomenti)
		McpSyncHttpClientRequestCustomizer mockCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);

		// 2) Costruisci il transport con il customizer asincrono derivato dal sync
		HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
			.asyncHttpRequestCustomizer(McpAsyncHttpClientRequestCustomizer.fromSync(mockCustomizer))
			.build();

		// 3) CONNECT: deve invocare il customizer con metodo GET su /sse
		StepVerifier
			.create(customizedTransport.connect(Function.identity())
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
			.verifyComplete();

		// Verifica customizer su GET
		verify(mockCustomizer).customize(any(RequestBuilder.class), eq("GET"),
				eq(Utils.resolveUri(URI.create(host), "/sse")), isNull(), eq(context));
		clearInvocations(mockCustomizer);

		// 4) Prepara un messaggio JSON-RPC di test
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.<String, Object>singletonMap("key", "value"));

		// 5) Imposta manualmente il messageEndpoint per evitare dipendenze dalla SSE
		setMessageEndpointViaReflection(customizedTransport, "/message?sessionId=test-session");

		// 6) SEND: deve invocare il customizer con metodo POST verso l'endpoint, con body
		// JSON
		StepVerifier
			.create(customizedTransport.sendMessage(testMessage)
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
			.verifyComplete();

		// Cattura e verifica gli argomenti della POST
		@SuppressWarnings("unchecked")
		ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);

		verify(mockCustomizer).customize(any(RequestBuilder.class), eq("POST"), uriCaptor.capture(), eq(
				"{\"jsonrpc\":\"2.0\",\"method\":\"test-method\",\"id\":\"test-id\",\"params\":{\"key\":\"value\"}}"),
				eq(context));

		// L'URI deve essere host + endpoint (risolto correttamente)
		String expectedPrefix = host + "/message?sessionId=";
		assertThat(uriCaptor.getValue().toString()).startsWith(expectedPrefix);

		// Clean up
		customizedTransport.closeGracefully().block();
	}

	@Test
	void testAsyncRequestCustomizer() {
		McpAsyncHttpClientRequestCustomizer mockCustomizer = mock(McpAsyncHttpClientRequestCustomizer.class);
		when(mockCustomizer.customize(any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> Mono.just(invocation.getArguments()[0]));

		// Create a transport with the customizer
		HttpClientSseClientTransport customizedTransport = HttpClientSseClientTransport.builder(host)
			.asyncHttpRequestCustomizer(mockCustomizer)
			.build();

		// Connect
		StepVerifier
			.create(customizedTransport.connect(Function.identity())
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
			.verifyComplete();

		// Verify the customizer was called
		verify(mockCustomizer).customize(any(), eq("GET"),
				eq(UriComponentsBuilder.fromUriString(host).path("/sse").build().toUri()), isNull(), eq(context));
		clearInvocations(mockCustomizer);

		// Send test message
		JSONRPCRequest testMessage = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Collections.singletonMap("key", "value"));

		// Subscribe to messages and verify
		StepVerifier
			.create(customizedTransport.sendMessage(testMessage)
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
			.verifyComplete();

		// Verify the customizer was called
		ArgumentCaptor<URI> uriArgumentCaptor = ArgumentCaptor.forClass(URI.class);
		verify(mockCustomizer).customize(any(), eq("POST"), uriArgumentCaptor.capture(), eq(
				"{\"jsonrpc\":\"2.0\",\"method\":\"test-method\",\"id\":\"test-id\",\"params\":{\"key\":\"value\"}}"),
				eq(context));
		assertThat(uriArgumentCaptor.getValue().toString()).startsWith(host + "/message?sessionId=");

		// Clean up
		customizedTransport.closeGracefully().block();
	}

	/**
	 * Utility: imposta il messageEndpoint della superclasse via reflection. Evita di
	 * dover aprire una SSE reale; utile nei test unitari.
	 * @throws SecurityException
	 * @throws NoSuchFieldException
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 */
	private static void setMessageEndpointViaReflection(HttpClientSseClientTransport transport, String endpoint)
			throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
		Field f = HttpClientSseClientTransport.class.getDeclaredField("messageEndpoint");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		AtomicReference<String> ref = (AtomicReference<String>) f.get(transport);
		ref.set(endpoint);
	}

}
