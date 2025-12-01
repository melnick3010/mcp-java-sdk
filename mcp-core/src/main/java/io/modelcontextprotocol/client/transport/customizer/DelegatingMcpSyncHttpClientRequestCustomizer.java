/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import org.apache.http.client.methods.RequestBuilder;
import java.util.List;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.util.Assert;

/**
 * Composable {@link McpSyncHttpClientRequestCustomizer} that applies multiple
 * customizers, in order.
 *
 * @author Daniel Garnier-Moiroux
 */
public class DelegatingMcpSyncHttpClientRequestCustomizer implements McpSyncHttpClientRequestCustomizer {

	private final List<McpSyncHttpClientRequestCustomizer> delegates;

	public DelegatingMcpSyncHttpClientRequestCustomizer(List<McpSyncHttpClientRequestCustomizer> customizers) {
		Assert.notNull(customizers, "Customizers must not be null");
		this.delegates = customizers;
	}

	@Override
	public void customize(RequestBuilder builder, String method, URI endpoint, String body,
			McpTransportContext context) {
		this.delegates.forEach(delegate -> delegate.customize(builder, method, endpoint, body, context));
	}

}
