
# MCP – Strategia di **Logging Strutturato** (Java 8)

> Documento di riferimento per i futuri test di integrazione Client/Server (SSE + HTTP) del protocollo MCP.

## 1. Scopo e principi

- Ogni operazione deve essere tracciata con **eventi JSON strutturati**, parsabili e correlabili.
- Il logging descrive fedelmente l’invariante MCP: **una richiesta → un solo terminale** (SUCCESS/ERROR).
- Campi **obbligatori e stabili** su tutti gli eventi: `side`, `channel`, `event`, `ts`, `thread`, `sessionId` (se esiste), più `jsonrpc`, `corr`, `meta` quando applicabili.
- **Niente duplicati testuali**: gli eventi canonici JSON sostituiscono i log “umani”.

## 2. Schema dei campi (contratto)

Ogni riga di log deve seguire questa struttura:

```json
{
  "side": "CLIENT|SERVER",
  "channel": "SSE|HTTP|ASYNC|TEST",
  "event": "<NomeEvento>",
  "ts": "2025-12-15T11:33:56.992Z",
  "thread": "http-nio-...|http-sse-client-...|main",
  "sessionId": "<uuid>",
  "jsonrpc": {
    "method": "initialize|tools/call|notifications/initialized|...",
    "kind":   "REQUEST|RESPONSE|NOTIFICATION",
    "id":     "<jsonrpc id>"
  },
  "corr": {
    "initiatorId": "<id originario>",
    "parentId":    "<id figlio (server→client)>",
    "seq":         0,
    "pending":     0
  },
  "meta": {
    "endpoint": "http://.../mcp/message?sessionId=...",
    "caller":   "<origine del log>",
    "payloadHash": "<opzionale>",
    "leftRunning": true
  },
  "outcome": {
    "status": "PENDING|SUCCESS|ERROR|CLOSED",
    "cause":  "Timeout|IOException|Broken pipe|..."
  }
}
```

**Nota**: tutti i metadati vanno sotto **`meta.*`**; le correlazioni sotto **`corr.*`**. Evitare `extra.meta` (se presente, la utility la normalizza in `meta`).

## 3. Tassonomia degli eventi

### 3.1 Server
- `S_SSE_ENDPOINT` — annuncia l’endpoint di messaging (top-level `meta.endpoint`).
- `S_RECV_REQ_HTTP` — ricezione HTTP **REQUEST** / **NOTIFICATION** (valorizzare `jsonrpc.method/kind/id`).
- `S_PREP_SSE_RESP` — preparazione della **RESPONSE** via SSE (prima dell’emit).
- `S_SSE_SEND` — invio evento SSE (`kind=REQUEST|RESPONSE|NOTIFICATION`).
- `S_RECV_RESP_HTTP` — ricezione HTTP **RESPONSE** (client→server per richieste server→client).
- `S_DELIVERED_TO_PENDING` — consegna alla sink pendente (`corr.pending` prima/dopo).
- `S_REQ_COMPLETED` — terminale lato server per la richiesta esterna (`jsonrpc.method`, `id`, `outcome.status`).
- `S_SSE_CLOSED` — chiusura SSE con **`outcome.cause`** (`Timeout|Error|...`) e `corr.pending`.
- `S_ASYNC_COMPLETE` — **UNICO** terminale asincrono dell’AsyncContext (emesso una sola volta per `sessionId`).

### 3.2 Client
- `C_SEND_REQ` — emissione `sendRequest` (REQUEST esterna).
- `C_SSE_RAW` — evento SSE ricevuto (estrarre `id/kind/sessionId/eventType`, tenere il payload solo come hash/opzionale).
- `C_SSE_PARSED` — parsing dell’evento SSE (valorizzare `jsonrpc.method` se noto via pending).
- `C_DISPATCH` — routing (`outcome.route=LOCAL|POST`), includere `jsonrpc.id/method/kind` e `corr.initiatorId`.
- `C_POST_START` / `C_POST_OK` — POST di risposta a richieste server→client.
- `C_RECV_RESP_COMPLETE` — **UNICO** terminale lato client per l’`id` (SUCCESS/ERROR).
- `C_SSE_READER_TIMEOUT` — timeout del reader con `outcome.stack` (sintetico) e `outcome.message`.
- `C_TEST_STEP` — passi di test (ridotti e non ridondanti, es. `phase=handshake|handshake-complete|teardown`).

## 4. Invarianti (validator)

1. Per ogni `jsonrpc.id` esterno: **esattamente un** terminale client `C_RECV_RESP_COMPLETE` **o** un errore `C_ERROR` (timeout/transport).
2. Per ogni `id` lato server: **esattamente un** `S_REQ_COMPLETED`.
3. Nessun evento di invio (`S_SSE_SEND`) senza la controparte lato client (`C_SSE_RAW` → `C_SSE_PARSED` → `C_RECV_RESP_COMPLETE`).
4. `S_SSE_CLOSED` non deve comparire **dopo** un graceful close; se compare, deve avere `outcome.cause` e non deve coesistere con `S_ASYNC_COMPLETE` duplicati.
5. `S_ASYNC_COMPLETE`: **una sola** riga per `sessionId` (idempotente).

## 5. Pattern di implementazione (Java 8)

### 5.1 Utility di logging — normalizzazione `extra.meta → meta`

```java
public static void logEvent(Logger logger, String side, String channel, String event, String sessionId,
        Map<String, Object> jsonrpc, Map<String, Object> corr, Map<String, Object> outcome,
        Map<String, Object> extra) {
    try {
        Map<String, Object> o = new java.util.LinkedHashMap<String, Object>();
        o.put("side", side);
        o.put("channel", channel);
        o.put("event", event);
        o.put("ts", java.time.Instant.now().toString());
        o.put("thread", Thread.currentThread().getName());
        if (sessionId != null) o.put("sessionId", sessionId);
        if (jsonrpc != null)  o.put("jsonrpc", jsonrpc);
        if (corr != null)     o.put("corr", corr);
        if (outcome != null)  o.put("outcome", outcome);
        if (extra != null && !extra.isEmpty()) {
            Object maybeMeta = extra.get("meta");
            Map<String, Object> extraWithoutMeta = new java.util.LinkedHashMap<String, Object>();
            for (java.util.Map.Entry<String, Object> e : extra.entrySet()) {
                if (!"meta".equals(e.getKey())) extraWithoutMeta.put(e.getKey(), e.getValue());
            }
            if (maybeMeta instanceof java.util.Map) {
                @SuppressWarnings("unchecked") java.util.Map<String, Object> meta = (java.util.Map<String, Object>) maybeMeta;
                o.put("meta", meta);
            }
            if (!extraWithoutMeta.isEmpty()) o.put("extra", extraWithoutMeta);
        }
        logger.info(new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .writeValueAsString(o));
    } catch (Exception e) {
        logger.warn("Failed to emit structured MCP log: {}", e.getMessage());
    }
}
```

### 5.2 Stato sessione & Listener (guardie e cause)

```java
// Stato idempotente
public final class SseSessionState {
  public enum Phase { OPEN, CLOSING_GRACEFUL, CLOSED_GRACEFUL, TIMEOUT_CLOSING, CLOSED_TIMEOUT, ERROR_CLOSING, CLOSED_ERROR }
  private final java.util.concurrent.atomic.AtomicReference<Phase> phase = new java.util.concurrent.atomic.AtomicReference<Phase>(Phase.OPEN);
  public boolean beginGracefulClose(){ return phase.compareAndSet(Phase.OPEN, Phase.CLOSING_GRACEFUL);} 
  public boolean completeGracefulClose(){ return phase.compareAndSet(Phase.CLOSING_GRACEFUL, Phase.CLOSED_GRACEFUL);} 
  public boolean isClosing(){ Phase p=phase.get(); return p==Phase.CLOSING_GRACEFUL||p==Phase.TIMEOUT_CLOSING||p==Phase.ERROR_CLOSING;} 
  public boolean isClosed(){ Phase p=phase.get(); return p==Phase.CLOSED_GRACEFUL||p==Phase.CLOSED_TIMEOUT||p==Phase.CLOSED_ERROR;} 
}

// Listener con callback per complete+log unico
public interface AsyncCompleteLogger { void completeAndLogOnce(String sessionId, javax.servlet.AsyncContext async, String caller); }

public final class SseAsyncListener implements javax.servlet.AsyncListener {
  private final org.slf4j.Logger logger; private final String sessionId; private final SseSessionState state; private final AsyncCompleteLogger asyncLogger;
  public SseAsyncListener(org.slf4j.Logger l, String sid, SseSessionState st, AsyncCompleteLogger cb){logger=l;sessionId=sid;state=st;asyncLogger=cb;}
  public void onComplete(javax.servlet.AsyncEvent e){} // no-op
  public void onStartAsync(javax.servlet.AsyncEvent e){}
  public void onTimeout(javax.servlet.AsyncEvent e) throws java.io.IOException {
    if (state.isClosed()||state.isClosing()) return;
    // log chiusura per Timeout
    java.util.Map<String,Object> corr=new java.util.HashMap<String,Object>(); corr.put("pending",0);
    java.util.Map<String,Object> out=new java.util.HashMap<String,Object>(); out.put("status","CLOSED"); out.put("cause","Timeout");
    io.modelcontextprotocol.logging.McpLogging.logEvent(logger,"SERVER","SSE","S_SSE_CLOSED",sessionId,null,corr,out,null);
    asyncLogger.completeAndLogOnce(sessionId,e.getAsyncContext(),"HttpServletSseServerTransportProvider.onTimeout");
    state.completeGracefulClose(); // oppure completeTimeoutClose() se implementato
  }
  public void onError(javax.servlet.AsyncEvent e) throws java.io.IOException {
    if (state.isClosed()||state.isClosing()) return;
    java.util.Map<String,Object> corr=new java.util.HashMap<String,Object>(); corr.put("pending",0);
    java.util.Map<String,Object> out=new java.util.HashMap<String,Object>(); out.put("status","CLOSED");
    Throwable t=e.getThrowable(); out.put("cause", t!=null?t.getClass().getSimpleName():"Unknown");
    io.modelcontextprotocol.logging.McpLogging.logEvent(logger,"SERVER","SSE","S_SSE_CLOSED",sessionId,null,corr,out,null);
    asyncLogger.completeAndLogOnce(sessionId,e.getAsyncContext(),"HttpServletSseServerTransportProvider.onError");
    state.completeGracefulClose(); // oppure completeErrorClose()
  }
}
```

### 5.3 Provider — callback e centralizzazione di `S_ASYNC_COMPLETE`

```java
// Nel provider: flag per sessione
private final java.util.Map<String, java.util.concurrent.atomic.AtomicBoolean> asyncLogged =
  new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>();

// Callback
private final AsyncCompleteLogger asyncLogger = new AsyncCompleteLogger(){
  public void completeAndLogOnce(String sid, javax.servlet.AsyncContext async, String caller){
    try{ async.complete(); }catch(Throwable ignore){}
    java.util.concurrent.atomic.AtomicBoolean f = asyncLogged.get(sid);
    if (f==null){ f=new java.util.concurrent.atomic.AtomicBoolean(false); asyncLogged.put(sid,f);} 
    if (f.compareAndSet(false,true)){
      java.util.Map<String,Object> meta=new java.util.HashMap<String,Object>(); meta.put("caller",caller);
      java.util.Map<String,Object> extra=new java.util.HashMap<String,Object>(); extra.put("meta",meta);
      io.modelcontextprotocol.logging.McpLogging.logEvent(logger,"SERVER","ASYNC","S_ASYNC_COMPLETE",sid,null,null,null,extra);
    }
  }
};

// Wiring nel doGet(...)
asyncContext.addListener(new SseAsyncListener(logger, sessionId, new SseSessionState(), asyncLogger));
```

### 5.4 Chiusura graceful (idempotente, timeout-safe)

```java
private void closeSessionWithDrain(final String sessionId, final javax.servlet.AsyncContext asyncContext){
  // Disabilita timeout subito
  try{ asyncContext.setTimeout(0L);}catch(Throwable ignore){}
  // Cancella heartbeat/scheduler se presente
  // ...
  // Calcola pending e decide immediate/deferred
  int pending=0; io.modelcontextprotocol.server.McpServerSession sess=this.sessions.get(sessionId);
  if (sess!=null) pending=sess.pendingResponsesCount();
  if (pending>0){
    long delay = Math.max(WRITE_ERROR_GRACE_MS, safeRequestTimeout(sess));
    final String sidFinal=sessionId;
    this.dedicatedScheduler.schedule(new java.lang.Runnable(){
      public void run(){ removeSession(sidFinal); asyncLogger.completeAndLogOnce(sidFinal, asyncContext,
        "HttpServletSseServerTransportProvider.closeSessionWithDrain(deferred)"); }
    }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
  } else {
    removeSession(sessionId); asyncLogger.completeAndLogOnce(sessionId, asyncContext,
      "HttpServletSseServerTransportProvider.closeSessionWithDrain(immediate)");
  }
}
```

### 5.5 `S_SSE_ENDPOINT` coerente

```java
// Emetti meta.endpoint al top-level (niente extra.meta)
java.util.Map<String,Object> meta=new java.util.HashMap<String,Object>(); meta.put("endpoint", endpointUrl);
java.util.Map<String,Object> extra=new java.util.HashMap<String,Object>(); extra.put("meta", meta);
io.modelcontextprotocol.logging.McpLogging.logEvent(logger,"SERVER","SSE","S_SSE_ENDPOINT",sessionId,null,null,null,extra);
```

### 5.6 Terminali lato client/server

- **Client**: emettere `C_RECV_RESP_COMPLETE` (channel `SSE`) con `jsonrpc.method/kind/id` e `outcome.status`.
- **Server**: emettere `S_REQ_COMPLETED` per ogni `REQUEST` esterna (in `completeAsyncContextEmpty(...)`), con `jsonrpc.method/kind/id` e `corr.pending`.

## 6. Validator (audit dei log)

- Raggruppa gli eventi per `sessionId` e `jsonrpc.id`.
- Verifica le invarianti della sezione 4; falla fallire se trovi:
  - doppi `S_ASYNC_COMPLETE` per `sessionId`;
  - `onTimeout` post‑graceful (ossia `S_SSE_CLOSED` con cause `Timeout` dopo `S_ASYNC_COMPLETE`);
  - eventi di invio senza controparte (`S_SSE_SEND` senza `C_SSE_RAW/PARSED/COMPLETE`).
- Genera diagrammi (Mermaid/PlantUML) per ogni `sessionId` e un report CSV.

## 7. Checklist di accettazione (per ogni test)

- `S_SSE_ENDPOINT` presente e coerente (`meta.endpoint`).
- `S_ASYNC_COMPLETE` presente **una sola volta** per `sessionId`.
- `S_SSE_CLOSED` con `outcome.cause` **solo** su timeout/errore.
- `S_REQ_COMPLETED` presente per ogni `REQUEST` esterna.
- `C_RECV_RESP_COMPLETE` presente per ogni `id` lato client.
- Nessun log testuale duplicato degli eventi JSON.

## 8. Note operative

- **Java 8**: niente `Map.of(...)`; usa `HashMap`/`Collections.*`.
- **MDC** (facoltativo): popola `sessionId`, `jsonrpc.id`, `jsonrpc.method`, `corr.initiatorId` in MDC per avere log coerenti su tutti i logger.
- `C_TEST_STEP`: emettere una sola riga per fase.

---

**Versione**: v1.0 – 2025‑12‑16

