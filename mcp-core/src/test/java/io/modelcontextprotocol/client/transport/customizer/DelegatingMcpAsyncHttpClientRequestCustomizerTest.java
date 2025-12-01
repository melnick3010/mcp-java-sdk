
/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.http.Header;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import io.modelcontextprotocol.common.McpTransportContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DelegatingMcpAsyncHttpClientRequestCustomizer}.
 *
 * @author Daniel Garnier-Moiroux
 */
class DelegatingMcpAsyncHttpClientRequestCustomizerTest {

	private static final URI TEST_URI = URI.create("https://example.com");

	// Java 8: Apache HttpClient RequestBuilder in luogo di
	// java.net.http.HttpRequest.Builder
	private final RequestBuilder TEST_BUILDER = RequestBuilder.create("GET").setUri(TEST_URI);

	@Test
	void delegates() {
		// Mock esplicito con ritorno Mono<RequestBuilder> (il builder passato)
		McpAsyncHttpClientRequestCustomizer mockCustomizer = mock(McpAsyncHttpClientRequestCustomizer.class);
		when(mockCustomizer.customize(any(RequestBuilder.class), any(String.class), any(URI.class), any(String.class),
				any(McpTransportContext.class)))
			.thenAnswer(invocation -> Mono.just((RequestBuilder) invocation.getArguments()[0]));

		DelegatingMcpAsyncHttpClientRequestCustomizer customizer = new DelegatingMcpAsyncHttpClientRequestCustomizer(
				Arrays.asList(mockCustomizer));

		McpTransportContext context = McpTransportContext.EMPTY;

		StepVerifier
			.create(customizer.customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}", context))
			.expectNext(TEST_BUILDER) // stesso instance reference del builder
			.verifyComplete();

		verify(mockCustomizer).customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}", context);
	}

	@Test
	void delegatesInOrder() {
		final String headerName = "x-test";

		// Ogni customizer "clona" il builder corrente e aggiunge un header.
		// In Apache HttpClient usiamo RequestBuilder.copy(builder.build()) per copiare
		// stato+headers.
		DelegatingMcpAsyncHttpClientRequestCustomizer customizer = new DelegatingMcpAsyncHttpClientRequestCustomizer(
				Arrays.asList(
						(builder, method, uri, body, ctx) -> Mono
							.just(RequestBuilder.copy(builder.build()).addHeader(headerName, "one")),
						(builder, method, uri, body, ctx) -> Mono
							.just(RequestBuilder.copy(builder.build()).addHeader(headerName, "two"))));

		Flux<String> valuesStream = Mono
			.from(customizer.customize(TEST_BUILDER, "GET", TEST_URI, "{\"everybody\": \"needs somebody\"}",
					McpTransportContext.EMPTY))
			.map(RequestBuilder::build) // -> HttpUriRequest
			.flatMapIterable(req -> {
				Header[] headers = req.getHeaders(headerName);
				List<String> values = new ArrayList<String>(headers.length);
				for (Header h : headers) {
					values.add(h.getValue());
				}
				return values;
			});

		StepVerifier.create(valuesStream).expectNext("one").expectNext("two").verifyComplete();
	}

	@Test
	void constructorRequiresNonNull() {
		assertThatThrownBy(() -> new DelegatingMcpAsyncHttpClientRequestCustomizer(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Customizers must not be null");
	}

}
