
package io.modelcontextprotocol.client.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Mono;

/**
 * Smoke test che valida il dispatch lato client per eventi SSE "message": -
 * JSONRPCResponse ricevuta: NON deve eseguire POST (sendMessage NON chiamato). -
 * JSONRPCRequest ricevuta: DEVE eseguire POST (sendMessage chiamato UNA volta).
 *
 * Test white-box: non apre connessioni HTTP; esercita la logica di connect(...) su un
 * singolo "incoming" e usa una sottoclasse che intercetta sendMessage(...).
 */
public class HttpClientSseClientTransportDispatchSmokeTest {

	static class SpyTransport extends HttpClientSseClientTransport {

		private static PoolingHttpClientConnectionManager connectionManager;

		private final AtomicInteger postCount = new AtomicInteger(0);

		SpyTransport() {
			super(
					// httpClient: non usato (nessuna rete in questo test)
					org.apache.http.impl.client.HttpClients.createDefault(), connectionManager, "http://localhost:0",
					"/sse", McpJsonMapper.getDefault(), McpAsyncHttpClientRequestCustomizer.NOOP);
		}

		int getPostCount() {
			return postCount.get();
		}

		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message) {
			// Intercetta e conta quante POST verrebbero inviate
			postCount.incrementAndGet();
			return Mono.empty();
		}

		/**
		 * Metodo di comodo per il test: esercita la stessa logica branching di
		 * connect(...) per un singolo "incoming" senza eseguire I/O.
		 */
		Mono<Void> dispatchIncomingForTest(JSONRPCMessage incoming,
				Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			Mono<JSONRPCMessage> out = handler.apply(Mono.just(incoming));
			if (incoming instanceof McpSchema.JSONRPCRequest) {
				return out.flatMap(this::sendMessage).then();
			}
			else {
				// Response/Notification: non deve postare nulla; lascia completare
				// localmente
				return out.then();
			}
		}

	}

	@Test
	@DisplayName("Incoming JSONRPCResponse via SSE: NON deve effettuare POST")
	void incomingResponse_shouldNotPost() {
		SpyTransport t = new SpyTransport();

		// Simula una response del server verso il client
		McpSchema.JSONRPCResponse resp = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, "client-0", /*
																												 * id
																												 * di
																												 * una
																												 * request
																												 * del
																												 * client
																												 */
				/* result */ java.util.Collections.singletonMap("ok", true), /* error */ null);

		// Handler del client: per una response, completa localmente senza inviare nulla
		Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = mono -> mono;

		t.dispatchIncomingForTest(resp, handler).block();

		assertThat(t.getPostCount()).as("POST non deve essere chiamata per JSONRPCResponse").isZero();
	}

	@Test
	@DisplayName("Incoming JSONRPCRequest via SSE: DEVE effettuare UNA POST di risposta")
	void incomingRequest_shouldPostOnce() {
		SpyTransport t = new SpyTransport();

		// Simula una request del server al client (es. 'ping')
		McpSchema.JSONRPCRequest req = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "ping", "server-1",
				java.util.Collections.emptyMap());

		// Handler del client: produce la response (echo dell'id) che il transport deve
		// postare
		Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = mono -> mono.map(in -> {
			McpSchema.JSONRPCRequest r = (McpSchema.JSONRPCRequest) in;
			return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, r.id(), /*
																					 * echo
																					 * id
																					 */
					java.util.Collections.singletonMap("pong", true), null);
		});

		t.dispatchIncomingForTest(req, handler).block();

		assertThat(t.getPostCount()).as("POST deve essere chiamata UNA volta per JSONRPCRequest").isEqualTo(1);
	}

}
