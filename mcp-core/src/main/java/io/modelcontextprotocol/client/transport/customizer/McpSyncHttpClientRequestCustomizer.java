/*
 * Copyright 2024-2025 the original author or authors.
 */
package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;

import org.apache.http.client.methods.RequestBuilder;
import reactor.util.annotation.Nullable;

import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.common.McpTransportContext;

/**
 * Customize {@link RequestBuilder} before executing the request, either in SSE or
 * Streamable HTTP transport. Do not rely on thread-locals in this implementation, instead
 * use {@link SyncSpec#transportContextProvider} to extract context, and then consume it
 * through {@link McpTransportContext}.
 *
 * @author Daniel Garnier-Moiroux
 */
public interface McpSyncHttpClientRequestCustomizer {

	void customize(RequestBuilder builder, String method, URI endpoint, @Nullable String body,
			McpTransportContext context);

}
