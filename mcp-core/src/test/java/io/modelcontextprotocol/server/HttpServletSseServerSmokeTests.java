
package io.modelcontextprotocol.server;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests elementari: una funzionalità alla volta.
 * Collega le tue implementazioni a startTomcatOn(port) / stopTomcat().
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpServletSseServerSmokeTests {

    // === Parametri adattabili ai tuoi mapping attuali (come da log) ===
    private static final String HOST = "localhost";
    private static final String SSE_PATH = "/somePath/sse"; // dai log: HttpServletSseServerTransportProvider - SSE doGet()
    // Il messageEndpoint viene annunciato dal server dopo initialize: /otherPath/mcp/message?sessionId=...

    // Stato per-test
    private int port;
    private ServerHandle server; // incapsula la tua istanza Tomcat

    // Valori catturati durante l'handshake
    private Optional<String> announcedMessageEndpoint = Optional.empty();

    // ====== Lifecycle per-test con porta dedicata ======
    @BeforeEach
    void setUp() throws Exception {
        port = findAvailablePort();
        server = startTomcatOn(port);
        assertNotNull(server, "Start Tomcat deve restituire un handle non nullo");
    }

    @AfterEach
    void tearDown() throws Exception {
        stopTomcat(server);
        server = null;
        announcedMessageEndpoint = Optional.empty();
    }

    // ====== Test 1: avvio del server (solo readiness HTTP) ======
    @Test
    @Order(1)
    void serverStartsOnChosenPort_andRespondsToTcpHandshake() throws Exception {
        // Attende che il connector HTTP sia pronto (status line disponibile)
        assertTrue(waitForHttpReady("/", Duration.ofSeconds(5)),
                   "Il server non è pronto su / entro il timeout");

        // Effettua una GET "neutra" su root (ci interessa solo che non sia Connection Refused/Reset)
        HttpURLConnection conn = open("GET", "/");
        int code = conn.getResponseCode();
        // In molti embed Tomcat / senza mapping, la root può essere 404: va bene, il punto è che risponde.
        assertTrue(code >= 200 && code < 500, "Il server deve rispondere (2xx/3xx/4xx). Codice: " + code);
        conn.disconnect();
    }

    // ====== Test 2: endpoint SSE risponde con content-type corretto ======
    @Test
    @Order(2)
    void sseEndpoint_isReachable_andReportsTextEventStream() throws Exception {
        assertTrue(waitForHttpReady(SSE_PATH, Duration.ofSeconds(3)),
                   "SSE non è pronta entro il timeout");

        HttpURLConnection conn = open("GET", SSE_PATH);
        int code = conn.getResponseCode();
        assertEquals(200, code, "La SSE dovrebbe rispondere 200");
        String ctype = conn.getHeaderField("Content-Type");
        assertNotNull(ctype, "Content-Type mancante su SSE");
        assertTrue(ctype.toLowerCase().contains("text/event-stream"),
                   "Content-Type atteso text/event-stream, trovato: " + ctype);
        conn.disconnect();
    }

    // ====== Test 3: handshake initialize (solo verifica di announce del messageEndpoint) ======
    @Test
    @Order(3)
    void initialize_returnsMessageEndpoint() throws Exception {
        // Precondizione: SSE pronta (evita race)
        assertTrue(waitForHttpReady(SSE_PATH, Duration.ofSeconds(3)),
                   "SSE non pronta, impossibile inizializzare il client");

        // Invochi qui la tua sequenza di client initialize (sincrona),
        // che nei log porta a:
        // - "Client initialize request - Protocol: 2024-11-05 ..."
        // - "Server response with Protocol: 2024-11-05 ..."
        // - "initialized: protocol=2024-11-05"
        // - e stampa del messageEndpoint = /otherPath/mcp/message?sessionId=...
        // Sostituisci questo stub con la tua chiamata reale:
        announcedMessageEndpoint = performClientInitializeAndCaptureMessageEndpoint();

        assertTrue(announcedMessageEndpoint.isPresent(),
                   "initialize deve annunciare un messageEndpoint non nullo");
        assertTrue(announcedMessageEndpoint.get().startsWith("/otherPath/mcp/message?sessionId="),
                   "messageEndpoint inatteso: " + announcedMessageEndpoint.get());
    }

    // ====== Test 4: message endpoint risponde (POST/GET base), senza validare la logica applicativa ======
    @Test
    @Order(4)
    void messageEndpoint_acceptsHttpCall_basic() throws Exception {
        // Prepara: initialize per ottenere l'endpoint (se non già eseguito)
        if (!announcedMessageEndpoint.isPresent()) {
            assertTrue(waitForHttpReady(SSE_PATH, Duration.ofSeconds(3)),
                       "SSE non pronta, impossibile inizializzare il client");
            announcedMessageEndpoint = performClientInitializeAndCaptureMessageEndpoint();
        }
        String path = announcedMessageEndpoint.orElseThrow(() ->
                new IllegalStateException("messageEndpoint assente; assicurati che initialize sia andato a buon fine"));

        // Esegue una richiesta "neutra" (qui GET; se nel tuo server è previsto POST, cambia di conseguenza)
        HttpURLConnection conn = open("GET", path);
        int code = conn.getResponseCode();
        assertTrue(code >= 200 && code < 500, "messageEndpoint deve rispondere (2xx/3xx/4xx). Codice: " + code);
        conn.disconnect();
    }

    // ====== Test 5: shutdown pulito (niente Connection Refused durante stop) ======
    @Test
    @Order(5)
    void serverShutsDown_gracefully() throws Exception {
        // Verifica che sia in ascolto
        assertTrue(waitForHttpReady("/", Duration.ofSeconds(3)), "Server non pronto prima dello shutdown");
        // Stop
        stopTomcat(server);
        // A server spento, una connessione nuova deve fallire con timeout (non con "reset" immediato durante stop)
        // Qui facciamo un check di non-prontezza (best-effort)
        assertFalse(waitForHttpReady("/", Duration.ofSeconds(2)),
                    "Il server risulta ancora pronto dopo shutdown (atteso non pronto)");
    }

    // ====== Helpers ======

    /** Trova una porta libera per il test corrente. */
    private static int findAvailablePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    /**
     * Avvia Tomcat embedded sulla porta indicata e ritorna un handle.
     * Sostituisci l’implementazione con la tua (ad es. builder del Tomcat, addContext, addServlet, start()).
     */

private ServerHandle startTomcatOn(int port) throws Exception {
    org.apache.catalina.startup.Tomcat tomcat = new org.apache.catalina.startup.Tomcat();
    tomcat.setPort(port);

    // Base dir temporanea
    tomcat.setBaseDir(java.nio.file.Files.createTempDirectory("tomcat-smoke").toString());

    // Context root "" (ROOT)
    String docBase = new java.io.File(".").getAbsolutePath();
    org.apache.catalina.Context ctx = tomcat.addContext("", docBase);

    // Mappiamo almeno una servlet "ping" sulla root,
    // così la GET "/" risponde 200.
    javax.servlet.http.HttpServlet pingServlet = new javax.servlet.http.HttpServlet() {
        @Override protected void doGet(javax.servlet.http.HttpServletRequest req,
                                       javax.servlet.http.HttpServletResponse resp)
                throws java.io.IOException {
            resp.setStatus(200);
            resp.setContentType("text/plain");
            resp.getWriter().write("OK");
        }
    };

    tomcat.addServlet("", "ping", pingServlet);
    ctx.addServletMappingDecoded("/", "ping");

    // Se hai già la tua servlet SSE, puoi aggiungere anche quella:
    // javax.servlet.http.HttpServlet sseServlet = new HttpServletSseServerTransportProvider(...);
    // tomcat.addServlet("", "mcpServlet", sseServlet);
    // ctx.addServletMappingDecoded(SSE_PATH, "mcpServlet");

    tomcat.start();
    return new ServerHandle(port, tomcat);
}


    /**
     * Arresta e distrugge l’istanza Tomcat corrente.
     */
    private void stopTomcat(ServerHandle handle) throws Exception {
        if (handle != null) {
            try {
                handle.stop();
            } finally {
                handle.destroy();
            }
        }
    }

    /** Attende che una GET su path restituisca una status line (2xx/3xx/4xx) entro timeout. */

private boolean waitForHttpReady(String path, Duration timeout) throws InterruptedException {
    long end = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < end) {
        // 1) prova handshake TCP
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(HOST, port), 500);
        } catch (IOException e) {
            Thread.sleep(50);
            continue; // server non pronto
        }

        // 2) prova GET HTTP
        try {
            HttpURLConnection conn = open("GET", path);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code >= 200 && code < 500) return true; // anche 404 va bene: server pronto
        } catch (IOException ignored) {
            // server non ancora pronto sul path
        }
        Thread.sleep(50);
    }
    return false;
}


    /** Apre una HttpURLConnection verso http://localhost:{port}{path} con method indicato. */
    private HttpURLConnection open(String method, String path) throws IOException {
        URL url = URI.create("http://" + HOST + ":" + port + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(1500);
        return conn;
    }

    /**
     * Esegue l'initialize del client MCP e cattura il messageEndpoint annunciato.
     * Sostituisci con la tua implementazione attuale (sincrona).
     */
    private Optional<String> performClientInitializeAndCaptureMessageEndpoint() {
        // TODO: collega la tua implementazione:
        // - crea il transport SSE puntando a http://localhost:port + SSE_PATH
        // - chiama client.initialize()
        // - leggi dal tuo client/transport il messageEndpoint annunciato
        // - restituisci Optional.of(messageEndpoint)
        return Optional.empty();
    }

    // ====== Piccolo wrapper per incapsulare start/stop/destroy del tuo Tomcat ======

private static final class ServerHandle {
    private final int port;
    private final org.apache.catalina.startup.Tomcat tomcat;

    private ServerHandle(int port, org.apache.catalina.startup.Tomcat tomcat) {
        this.port = port;
        this.tomcat = tomcat;
    }

    void stop() throws Exception { tomcat.stop(); }
    void destroy() throws Exception { tomcat.destroy(); }
}

}
