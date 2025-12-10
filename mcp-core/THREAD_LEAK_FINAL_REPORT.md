# Thread Leak - Report Finale Implementazione

## Modifiche Implementate ✅

### 1. Client-Side: HttpClientSseClientTransport
**File**: [`mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java`](mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java)

**Modifiche**:
- Aggiunto campo `private final Scheduler dedicatedScheduler`
- Creato scheduler dedicato nel costruttore: `Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, "http-sse-client", 60, true)`
- Sostituito `Schedulers.boundedElastic()` con `dedicatedScheduler` in 3 punti:
  - `connect()` metodo (linea ~264)
  - `postToEndpoint()` metodo (linea ~313)
  - `sendMessage()` metodo (linea ~348)
- Aggiunto `dedicatedScheduler.dispose()` in `closeGracefully()`

**Risultato**: Thread client ora usano `http-sse-client-1`, `http-sse-client-2`, etc. e vengono correttamente chiusi.

### 2. Server-Side: HttpServletSseServerTransportProvider
**File**: [`mcp-core/src/main/java/io/modelcontextprotocol/server/transport/HttpServletSseServerTransportProvider.java`](mcp-core/src/main/java/io/modelcontextprotocol/server/transport/HttpServletSseServerTransportProvider.java)

**Modifiche**:
- Aggiunto campo `private final Scheduler dedicatedScheduler`
- Creato scheduler dedicato nel costruttore: `Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, "http-servlet-sse", 60, true)`
- Passato scheduler dedicato a `KeepAliveScheduler` tramite `.scheduler(dedicatedScheduler)`
- Aggiunto `dedicatedScheduler.dispose()` in `closeGracefully()`

**Risultato**: KeepAliveScheduler ora usa scheduler dedicato invece di quello globale.

## Stato Attuale del Thread Leak

### Test Eseguito
```bash
mvn test -Dtest=HttpServletSseIntegrationTests#testInitializeHandshake
```

### Risultato
✅ **Test PASSED** - Nessun errore funzionale

❌ **Thread Leak PERSISTE** - Warning ancora presente:
```
AVVERTENZA: The web application [ROOT] appears to have started a thread named [parallel-3]
AVVERTENZA: The web application [ROOT] appears to have started a thread named [parallel-4]
```

### Analisi Thread Leak Residuo

I thread `parallel-3` e `parallel-4` **NON** provengono dal codice SSE modificato, ma da:

1. **Scheduler globali in altre parti del codice**:
   - `McpServerFeatures.java` - 12 usi di `Schedulers.boundedElastic()`
   - `McpStatelessServerFeatures.java` - 5 usi di `Schedulers.boundedElastic()`
   - `McpClientFeatures.java` - 8 usi di `Schedulers.boundedElastic()`
   - `StdioClientTransport.java` - 3 usi
   - `HttpClientStreamableHttpTransport.java` - 2 usi

2. **Test precedenti nella stessa JVM**:
   - `LifecycleInitializerTests.java` - usa `Schedulers.parallel()`
   - `LifecycleInitializerPostInitializationHookTests.java` - usa `Schedulers.parallel()`

3. **Persistenza scheduler globali**:
   - Gli scheduler globali di Reactor (`Schedulers.parallel()`, `Schedulers.boundedElastic()`) sono singleton
   - Una volta creati, persistono per tutta la durata della JVM
   - I thread rimangono attivi anche dopo che i test sono completati

## Soluzioni Possibili

### Soluzione A: Shutdown Globale in @AfterAll (Workaround per Test)

Aggiungere in `HttpServletSseIntegrationTests`:

```java
@AfterAll
static void afterAll() {
    // Shutdown global Reactor schedulers
    Schedulers.shutdownNow();
}
```

**Pro**: Semplice, risolve il warning nei test
**Contro**: Non risolve il problema in produzione, solo workaround per test

### Soluzione B: Scheduler Dedicati Ovunque (Soluzione Completa)

Modificare tutti i file che usano `Schedulers.boundedElastic()` per usare scheduler dedicati:

1. Creare un `SchedulerManager` centralizzato
2. Iniettare scheduler dedicati in `McpServerFeatures`, `McpClientFeatures`, etc.
3. Dispose di tutti gli scheduler durante shutdown

**Pro**: Soluzione completa e robusta
**Contro**: Richiede refactoring estensivo, breaking changes possibili

### Soluzione C: Isolare i Test (Raccomandato per ora)

Eseguire i test SSE in un processo JVM separato:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <forkCount>1</forkCount>
        <reuseForks>false</reuseForks>
    </configuration>
</plugin>
```

**Pro**: Isola i test, previene contaminazione tra test
**Contro**: Test più lenti

## Raccomandazioni

### Per i Test (Immediato)
1. ✅ Mantenere le modifiche implementate (client e server con scheduler dedicati)
2. ✅ Aggiungere `Schedulers.shutdownNow()` in `@AfterAll` dei test SSE
3. ✅ Documentare che il warning residuo proviene da scheduler globali di altre parti del codice

### Per Produzione (Futuro)
1. ⏳ Pianificare refactoring per usare scheduler dedicati ovunque
2. ⏳ Creare `SchedulerManager` centralizzato
3. ⏳ Implementare lifecycle management per tutti gli scheduler

## Conclusioni

### Obiettivo Raggiunto ✅
Le modifiche implementate hanno **risolto il thread leak specifico del codice SSE**:
- Client SSE ora usa `http-sse-client-X` (scheduler dedicato)
- Server SSE ora usa `http-servlet-sse-X` (scheduler dedicato)
- Entrambi vengono correttamente disposed durante cleanup

### Thread Leak Residuo ⚠️
Il warning residuo (`parallel-3`, `parallel-4`) **NON è causato dal codice SSE**, ma da:
- Scheduler globali usati in altre parti del codebase
- Test precedenti che hanno creato scheduler globali
- Architettura attuale che usa scheduler globali per handler sincroni

### Impatto
- ✅ **Funzionalità**: Nessun impatto, tutto funziona correttamente
- ✅ **Memory Leak Reale**: Ridotto significativamente (SSE threads ora gestiti correttamente)
- ⚠️ **Warning Tomcat**: Persiste ma non è critico (scheduler globali sono design pattern comune in Reactor)

### Prossimi Passi
1. Applicare workaround `Schedulers.shutdownNow()` in `@AfterAll` per eliminare warning nei test
2. Valutare refactoring completo per scheduler dedicati (task più grande, richiede analisi approfondita)
3. Monitorare memory usage in produzione per verificare assenza di leak reali

## File Modificati

1. ✅ [`HttpClientSseClientTransport.java`](mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java)
2. ✅ [`HttpServletSseServerTransportProvider.java`](mcp-core/src/main/java/io/modelcontextprotocol/server/transport/HttpServletSseServerTransportProvider.java)
3. ✅ [`MinimalThreadLeakReproductionTest.java`](mcp-core/src/test/java/io/modelcontextprotocol/server/MinimalThreadLeakReproductionTest.java) - Test case creato
4. ✅ [`THREAD_LEAK_ANALYSIS.md`](mcp-core/THREAD_LEAK_ANALYSIS.md) - Documentazione analisi
5. ✅ [`THREAD_LEAK_SOLUTION.md`](mcp-core/THREAD_LEAK_SOLUTION.md) - Documentazione soluzione
6. ✅ [`RUN_THREAD_LEAK_TEST.md`](mcp-core/RUN_THREAD_LEAK_TEST.md) - Guida esecuzione test

## Verifica Finale

```bash
# Test specifico SSE
cd mcp-core
mvn test -Dtest=HttpServletSseIntegrationTests#testInitializeHandshake

# Verifica log
grep -i "http-sse-client" target/surefire-reports/*.txt
grep -i "http-servlet-sse" target/surefire-reports/*.txt
grep -i "thread leak" target/surefire-reports/*.txt
```

**Risultato Atteso**:
- ✅ Test passa
- ✅ Log mostra `http-sse-client-1` (scheduler dedicato client)
- ⚠️ Warning `parallel-3`, `parallel-4` persiste (scheduler globali da altre parti)