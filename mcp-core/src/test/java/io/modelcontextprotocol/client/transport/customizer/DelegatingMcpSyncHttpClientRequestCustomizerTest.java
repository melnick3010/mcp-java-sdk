
/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;

import org.apache.http.Header;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.modelcontextprotocol.common.McpTransportContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DelegatingMcpSyncHttpClientRequestCustomizer}.
 *
 * @author Daniel Garnier-Moiroux
 */
class DelegatingMcpSyncHttpClientRequestCustomizerTest {

	private static final URI TEST_URI = URI.create("https://example.com");

	// Java 8: usa Apache HttpClient RequestBuilder al posto di
	// java.net.http.HttpRequest.Builder
	private final RequestBuilder TEST_BUILDER = RequestBuilder.create("GET").setUri(TEST_URI);

	@Test
	void delegates() {
		McpSyncHttpClientRequestCustomizer mockCustomizer = Mockito.mock(McpSyncHttpClientRequestCustomizer.class);

		DelegatingMcpSyncHttpClientRequestCustomizer customizer = new DelegatingMcpSyncHttpClientRequestCustomizer(
				Collections.singletonList(mockCustomizer));

		McpTransportContext context = McpTransportContext.EMPTY;

		customizer.customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}", context);

		verify(mockCustomizer).customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}", context);
	}

	@Test
	void delegatesInOrder() {
		final String testHeaderName = "x-test";

		DelegatingMcpSyncHttpClientRequestCustomizer customizer = new DelegatingMcpSyncHttpClientRequestCustomizer(
				Arrays.asList((builder, method, uri, body, ctx) -> builder.addHeader(testHeaderName, "one"),
						(builder, method, uri, body, ctx) -> builder.addHeader(testHeaderName, "two")));

		customizer.customize(TEST_BUILDER, "GET", TEST_URI, null, McpTransportContext.EMPTY);

		// Costruisci la request Apache e verifica l'ordine dei valori dell'header
		HttpUriRequest request = TEST_BUILDER.build();
		Header[] headers = request.getHeaders(testHeaderName);
		assertThat(headers).extracting(Header::getValue).containsExactly("one", "two");
	}

	@Test
	void constructorRequiresNonNull() {
		// Mantengo il test così com’è; se non esiste la classe Async, sostituisci con la
		// Sync.
		assertThatThrownBy(() -> new DelegatingMcpAsyncHttpClientRequestCustomizer(null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Customizers must not be null");
	}

}
