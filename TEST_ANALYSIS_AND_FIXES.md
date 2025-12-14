# HttpServletSseIntegrationTests - Analisi e Piano di Correzione

## Data Analisi
2025-12-14

## Sommario Esecutivo

Dopo l'analisi dei log e del codice, ho identificato le cause principali dei fallimenti nei test `HttpServletSseIntegrationTests`. I problemi sono legati a:

1. **Mock su classi final** (RISOLTO)
2. **Cambio di responsabilità Session ↔ Transport** (ANALIZZATO)
3. **Timeout nei test** (ANALIZZATO)
4. **Notifiche non ricevute** (ANALIZZATO)

## 1. Problemi Identificati

### A. Mock su Classi Final ✅ RISOLTO

**Problema**: Il test `testRootsWithoutCapability` usava `mock(CallToolResult.class)` su una classe final, causando errori "Cannot mock/spy final class".

**Soluzione Applicata**:
```java
// PRIMA (linea 648):
return mock(CallToolResult.class);

// DOPO:
return CallToolResult.builder()
    .addContent(new McpSchema.TextContent("Should not reach here"))
    .build();
```

**File Modificato**: `mcp-test/src/main/java/io/modelcontextprotocol/AbstractMcpClientServerIntegrationTests.java`

**Nota**: I test `testCreateMessageWithoutSamplingCapabilities` e `testCreateElicitationWithoutElicitationCapabilities` erano già stati corretti in precedenza (linee 119-129 e 366-373).

### B. Cambio di Responsabilità Session ↔ Transport

**Analisi del Codice**:

In `McpClientSession.java` (linee 116-132), il flusso è stato modificato:

```java
// Il handler ORA mappa davvero REQUEST → RESPONSE.
Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = mono -> Mono
    .from(connectHook.apply(mono.flatMap(msg -> {
        if (msg instanceof McpSchema.JSONRPCRequest) {
            // Genera la RESPONSE e RITORNALA (NO invio qui)
            return handleIncomingRequest((McpSchema.JSONRPCRequest) msg)
                .cast(McpSchema.JSONRPCMessage.class);
        }
        else {
            // RESPONSE/NOTIFICATION: gestisci localmente (pending/notify) e non postare
            McpClientSession.this.handle(msg);
            return Mono.just(msg);
        }
    })));
```

**Implicazioni**:
- La session NON invia più direttamente le response
- Il transport (`HttpClientSseClientTransport`) è responsabile del POST delle response
- Questo avviene nel metodo `postToEndpoint()` (linee 387-433)

**Flusso Attuale**:
1. SSE riceve REQUEST dal server
2. Handler della session genera RESPONSE
3. Transport esegue POST della response all'endpoint del server
4. Log: "CLIENT POST SUCCESS: status=200, elapsedMs=X, messageId=Y"

### C. Endpoint Readiness e Timeout

**Meccanismo di Readiness**:

In `HttpClientSseClientTransport.java`:

```java
// Readiness signal: completed when endpoint is discovered via SSE
private volatile CompletableFuture<String> endpointReady = new CompletableFuture<>();

// Nel metodo connect() (linea 311-314):
if (!endpointReady.isDone()) {
    endpointReady.complete(data);
    logger.info("SSE ENDPOINT DISCOVERED and readiness signal completed: {}", endpointForThisStream);
}

// Nel metodo sendMessage() (linea 445):
String endpoint = endpointReady.get(30, TimeUnit.SECONDS);
```

**Problemi Potenziali**:
- Se l'evento `endpoint` SSE non arriva, `sendMessage()` va in timeout dopo 30s
- I test potrebbero iniziare a inviare richieste prima che l'endpoint sia pronto
- Timeout di 45-60s nei test suggeriscono attese prolungate

### D. Notifiche Tools/Roots Non Ricevute

**Test Interessati**:
- `testRootsSuccess` (linee 591-634)
- `testToolsListChange` (linee 1700-1777)

**Pattern Comune**:
```java
await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
    assertThat(rootsRef.get()).containsAll(roots);
});
```

**Possibili Cause**:
1. La notifica viene inviata ma non propagata correttamente via SSE
2. Il POST della notifica non raggiunge il server
3. Il server non rilancia la notifica al client

## 2. Piano di Azione Raccomandato

### Passo 1: Verifica Stato Attuale ✅ COMPLETATO

- [x] Rimosso mock su `CallToolResult` in `testRootsWithoutCapability`
- [x] Verificato che altri test usano istanze reali

### Passo 2: Diagnostica Endpoint Readiness

**Azione**: Aggiungere logging diagnostico nei test per verificare:

```java
@BeforeEach
public void before() {
    // ... existing code ...
    
    // Dopo prepareClients():
    System.out.println("Waiting for SSE endpoint discovery...");
    // Verificare nei log: "SSE ENDPOINT DISCOVERED and readiness signal completed"
}
```

**Verifica nei Log**:
- Cercare: `SSE ENDPOINT DISCOVERED and readiness signal completed`
- Se assente, il problema è nella connessione SSE iniziale

### Passo 3: Verifica Flusso POST Response

**Azione**: Nei test che falliscono con timeout, verificare nei log:

1. `SSE MESSAGE PARSED: kind=REQUEST` - Il client riceve la REQUEST
2. `CLIENT HANDLER OUTPUT: kind=RESPONSE, id=X` - La session genera la RESPONSE
3. `CLIENT POST PREPARED: endpoint=..., id=X` - Il transport prepara il POST
4. `CLIENT POST SUCCESS: status=200, elapsedMs=X, messageId=X` - Il POST ha successo

**Se Manca uno di questi log**:
- Manca #1: Problema nella ricezione SSE
- Manca #2: Problema nell'handler della session
- Manca #3-4: Problema nel POST del transport

### Passo 4: Aumentare Timeout e Logging

**File**: `HttpServletSseIntegrationTests.java`

**Modifiche Suggerite**:

```java
@BeforeEach
public void before() {
    // ... existing code ...
    
    // Aumentare timeout per endpoint discovery
    assertTrue(waitForHttpReady(CUSTOM_SSE_ENDPOINT, Duration.ofSeconds(10)),
        "SSE non pronta: impossibile inizializzare il client nel test");
    
    // Aggiungere attesa esplicita per endpoint readiness
    System.out.println("Waiting for endpoint readiness signal...");
    Thread.sleep(2000); // Dare tempo all'SSE di stabilirsi
}
```

### Passo 5: Fix Specifici per Test Roots/Tools

**Test**: `testRootsSuccess` e simili

**Problema**: `await().atMost(Duration.ofSeconds(10))` fallisce con "actual not to be null"

**Soluzione**:

1. Verificare che il server invii la notifica:
```java
mcpServer = prepareSyncServerBuilder()
    .rootsChangeHandler((exchange, rootsUpdate) -> {
        System.out.println("SERVER: Received roots update: " + rootsUpdate);
        rootsRef.set(rootsUpdate);
    })
    .build();
```

2. Verificare che il client invii la notifica:
```java
mcpClient.rootsListChangedNotification();
System.out.println("CLIENT: Sent rootsListChanged notification");
```

3. Aumentare timeout se necessario:
```java
await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
    .untilAsserted(() -> {
        System.out.println("Checking rootsRef: " + rootsRef.get());
        assertThat(rootsRef.get()).isNotNull().containsAll(roots);
    });
```

## 3. Modifiche Già Implementate

### File: AbstractMcpClientServerIntegrationTests.java

**Linea 648**: Sostituito `mock(CallToolResult.class)` con istanza reale:

```java
return CallToolResult.builder()
    .addContent(new McpSchema.TextContent("Should not reach here"))
    .build();
```

**Impatto**: Risolve errori "Cannot mock final class" in `testRootsWithoutCapability`

## 4. Test da Eseguire

### Comando
```bash
cd mcp-core
mvn test -Dtest=HttpServletSseIntegrationTests
```

### Test Critici da Monitorare

1. **testRootsWithoutCapability** - Dovrebbe passare ora (mock rimosso)
2. **testCreateMessageWithRequestTimeoutSuccess** - Verificare timeout 45-60s
3. **testCreateElicitationWithRequestTimeoutSuccess** - Verificare timeout
4. **testRootsSuccess** - Verificare notifiche roots
5. **testToolsListChange** - Verificare notifiche tools

### Log da Cercare

**Successo Endpoint Discovery**:
```
SSE ENDPOINT DISCOVERED and readiness signal completed: /otherPath/mcp/message
```

**Successo POST Response**:
```
CLIENT POST SUCCESS: status=200, elapsedMs=X, messageId=Y
```

**Problemi**:
```
Timeout waiting for endpoint discovery
CLIENT postToEndpoint: endpoint=null, isClosing=false - Cannot POST
```

## 5. Raccomandazioni Finali

### Immediate (Alta Priorità)

1. ✅ **Rimuovere mock su classi final** - COMPLETATO
2. 🔄 **Eseguire test suite completa** - Verificare impatto delle modifiche
3. 🔄 **Analizzare log dettagliati** - Identificare pattern di fallimento

### Breve Termine (Media Priorità)

4. Aggiungere logging diagnostico nei test per endpoint readiness
5. Aumentare timeout nei test che falliscono sistematicamente
6. Verificare propagazione notifiche roots/tools

### Lungo Termine (Bassa Priorità)

7. Considerare refactoring dei test per renderli più robusti
8. Aggiungere test specifici per il flusso SSE REQUEST→RESPONSE
9. Documentare il nuovo flusso di responsabilità Session↔Transport

## 6. Note Tecniche

### Architettura SSE Attuale

```
Client                    Transport                  Server
  |                          |                          |
  |-- initialize() --------->|                          |
  |                          |-- GET /sse ------------->|
  |                          |<-- event: endpoint ------|
  |                          | (endpointReady.complete) |
  |                          |                          |
  |-- sendMessage() -------->|                          |
  |   (wait endpointReady)   |-- POST /message -------->|
  |                          |<-- 200 OK ---------------|
  |<-- response -------------|                          |
  |                          |                          |
  |                          |<-- event: message -------|
  |                          | (SSE REQUEST)            |
  |                          |-- handler.apply() ------>|
  |                          | (genera RESPONSE)        |
  |                          |-- POST /message -------->|
  |                          |<-- 200 OK ---------------|
```

### Timeout Configurati

- **Server**: 60s (`requestTimeout(Duration.ofSeconds(60))`)
- **Client**: 90s (`requestTimeout(Duration.ofSeconds(90))`)
- **Endpoint Discovery**: 30s (`endpointReady.get(30, TimeUnit.SECONDS)`)
- **Test Awaitility**: 5-10s (`await().atMost(Duration.ofSeconds(10))`)

### Thread Safety

- `endpointReady`: `CompletableFuture<String>` - thread-safe
- `messageEndpoint`: `AtomicReference<String>` - thread-safe
- `isClosing`: `volatile boolean` - thread-safe per lettura/scrittura singola

## 7. Conclusioni

La modifica principale nel codice è il **cambio di responsabilità** per l'invio delle response:
- **Prima**: La session inviava direttamente le response
- **Ora**: Il transport esegue il POST delle response generate dalla session

Questo richiede che i test:
1. Non verifichino più l'invio diretto dalla session
2. Verifichino invece il POST del transport (se necessario)
3. Attendano correttamente l'endpoint readiness prima di inviare richieste

Il fix del mock su `CallToolResult` risolve un problema immediato, ma potrebbero esserci altri problemi legati al timing e alla propagazione delle notifiche che richiedono ulteriori verifiche tramite esecuzione dei test.

---

**Prossimo Step**: Eseguire `mvn test -Dtest=HttpServletSseIntegrationTests` e analizzare i risultati.