
/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import reactor.core.publisher.Mono;

import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Simple {@link HandlerFilterFunction} which records calls made to an MCP server.
 *
 * Backported for Java 1.8:
 * - Replaced Java record with a final class (Call).
 * - Replaced List.copyOf with an immutable snapshot via Collections.unmodifiableList(new ArrayList<>(...)).
 * - Made generics explicit for HandlerFilterFunction/HandlerFunction.
 *
 * @author Daniel Garnier-Moiroux
 */
public class McpTestRequestRecordingExchangeFilterFunction
        implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private final List<Call> calls = new ArrayList<Call>();

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        // Lowercase header names (locale-stable) and join multi-values with comma
        final Map<String, String> headers = request.headers()
                .asHttpHeaders()
                .keySet()
                .stream()
                .collect(Collectors.toMap(
                        k -> k.toLowerCase(Locale.ROOT),
                        k -> String.join(",", request.headers().header(k))
                ));

        // Rebuild the request with captured body; keep a record of the call
        final Mono<ServerRequest> cr = request.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> {
                    this.calls.add(new Call(request.method(), headers, body));
                    return ServerRequest.from(request).body(body).build();
                });

        return cr.flatMap(next::handle);
    }

    /**
     * Returns an immutable snapshot of recorded calls.
     */
    public List<Call> getCalls() {
        return Collections.unmodifiableList(new ArrayList<Call>(calls));
    }

    /**
     * Java 8 replacement for the original record:
     * public record Call(HttpMethod method, Map<String, String> headers, String body) {}
     */
    public static final class Call {
        private final HttpMethod method;
        private final Map<String, String> headers;
        private final String body;

        public Call(HttpMethod method, Map<String, String> headers, String body) {
            this.method = method;
            this.headers = headers;
            this.body = body;
        }

        public HttpMethod getMethod() {
            return method;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getBody() {
            return body;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Call)) return false;
            Call that = (Call) o;
            return method == that.method
                    && Objects.equals(headers, that.headers)
                    && Objects.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method, headers, body);
        }

        @Override
        public String toString() {
            return "Call{" +
                    "method=" + method +
                    ", headers=" + headers +
                    ", body='" + body + '\'' +
                    '}';
        }
    }
}
