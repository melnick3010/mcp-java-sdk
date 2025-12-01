
/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import java.util.List;

import org.apache.http.client.methods.RequestBuilder;
import org.reactivestreams.Publisher;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * Composable {@link McpAsyncHttpClientRequestCustomizer} that applies multiple
 * customizers, in order.
 *
 * @author Daniel Garnier-Moiroux
 */
public class DelegatingMcpAsyncHttpClientRequestCustomizer implements McpAsyncHttpClientRequestCustomizer {

	private final List<McpAsyncHttpClientRequestCustomizer> customizers;

	public DelegatingMcpAsyncHttpClientRequestCustomizer(List<McpAsyncHttpClientRequestCustomizer> customizers) {
		Assert.notNull(customizers, "Customizers must not be null");
		this.customizers = customizers;
	}

	@Override
	public Publisher<RequestBuilder> customize(RequestBuilder builder, String method, URI endpoint, String body,
			McpTransportContext context) {
		Mono<RequestBuilder> result = Mono.just(builder);
		for (McpAsyncHttpClientRequestCustomizer customizer : this.customizers) {
			result = result.flatMap(b -> Mono.from(customizer.customize(b, method, endpoint, body, context)));
		}
		return result;
	}

}
