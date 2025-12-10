# Soluzione Thread Leak - Analisi Completa

## Problema Identificato

Il warning di thread leak è causato dall'uso di **scheduler globali di Reactor** (`Schedulers.parallel()` e `Schedulers.boundedElastic()`) che creano thread pool condivisi che persistono oltre il ciclo di vita dei test.

### Thread Leak Osservati

```
AVVERTENZA: The web application [ROOT] appears to have started a thread named [parallel-3] 
but has failed to stop it. This is very likely to create a memory leak.
```

## Root Cause Analysis

### 1. Client Side - HttpClientSseClientTransport ✅ RISOLTO

**Problema**: Usava `Schedulers.boundedElastic()` globale
**Soluzione Applicata**: Creato scheduler dedicato `http-sse-client`

```java
// Prima (globale)
.subscribeOn(Schedulers.boundedElastic())

// Dopo (dedicato)
private final Scheduler dedicatedScheduler = Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, 
    "http-sse-client", 60, true);
.subscribeOn(dedicatedScheduler)
```

**Risultato**: I thread `http-sse-client-1`, `http-sse-client-3`, etc. vengono correttamente chiusi con `dedicatedScheduler.dispose()` in `closeGracefully()`.

### 2. Server Side - Scheduler Globali ❌ ANCORA DA RISOLVERE

I thread `parallel-3` e `parallel-4` provengono da:

#### A. KeepAliveScheduler (mcp-core/src/main/java/io/modelcontextprotocol/util/KeepAliveScheduler.java)

```java
private Scheduler scheduler = Schedulers.boundedElastic();  // ← Scheduler globale
```

Usato da `HttpServletSseServerTransportProvider` per inviare keep-alive periodici.

#### B. McpServerFeatures e McpStatelessServerFeatures

Multipli usi di `Schedulers.boundedElastic()` per eseguire handler sincroni:

```java
return toolResult.subscribeOn(Schedulers.boundedElastic());  // ← Scheduler globale
```

#### C. McpClientFeatures

Multipli usi per consumer di notifiche:

```java
.subscribeOn(Schedulers.boundedElastic()).then()  // ← Scheduler globale
```

## Soluzioni Proposte

### Soluzione 1: Modificare KeepAliveScheduler (Raccomandato)

Permettere di passare uno scheduler dedicato invece di usare quello globale:

```java
public static class Builder {
    private Scheduler scheduler = null;  // null = usa dedicato
    
    public Builder scheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
        return this;
    }
    
    public KeepAliveScheduler build() {
        Scheduler actualScheduler = (scheduler != null) 
            ? scheduler 
            : Schedulers.newBoundedElastic(2, Integer.MAX_VALUE, "keep-alive", 60, true);
        // ...
    }
}
```

### Soluzione 2: Scheduler Dedicato in HttpServletSseServerTransportProvider

Creare uno scheduler dedicato per il transport provider:

```java
public class HttpServletSseServerTransportProvider {
    private final Scheduler dedicatedScheduler;
    
    protected HttpServletSseServerTransportProvider(...) {
        this.dedicatedScheduler = Schedulers.newBoundedElastic(
            4, Integer.MAX_VALUE, "http-servlet-sse", 60, true);
        
        if (keepAliveInterval != null) {
            this.keepAliveScheduler = KeepAliveScheduler.builder(...)
                .scheduler(dedicatedScheduler)  // Usa scheduler dedicato
                .build();
        }
    }
    
    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            isClosing.set(true);
            if (keepAliveScheduler != null) {
                keepAliveScheduler.stop();
            }
            // Dispose scheduler dedicato
            if (dedicatedScheduler != null && !dedicatedScheduler.isDisposed()) {
                dedicatedScheduler.dispose();
            }
        }).then(Mono.delay(Duration.ofMillis(100))).then();
    }
}
```

### Soluzione 3: Per i Test - Workaround Temporaneo

Nel test, chiamare `Schedulers.shutdownNow()` in `@AfterAll` invece che in `@AfterEach`:

```java
@AfterAll
static void afterAll() {
    // Shutdown global schedulers after ALL tests complete
    Schedulers.shutdownNow();
}
```

**Nota**: Questo è solo un workaround per i test, non risolve il problema in produzione.

## Implementazione Raccomandata

### Step 1: Modificare KeepAliveScheduler

File: `mcp-core/src/main/java/io/modelcontextprotocol/util/KeepAliveScheduler.java`

```java
public class KeepAliveScheduler {
    private final Scheduler scheduler;
    private final boolean ownsScheduler;  // true se creato internamente
    
    private KeepAliveScheduler(Builder builder) {
        if (builder.scheduler != null) {
            this.scheduler = builder.scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Schedulers.newBoundedElastic(
                2, Integer.MAX_VALUE, "keep-alive", 60, true);
            this.ownsScheduler = true;
        }
        // ... resto del costruttore
    }
    
    public void stop() {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        // Dispose scheduler solo se lo possediamo
        if (ownsScheduler && scheduler != null && !scheduler.isDisposed()) {
            scheduler.dispose();
        }
    }
    
    public static class Builder {
        private Scheduler scheduler;
        
        public Builder scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }
    }
}
```

### Step 2: Modificare HttpServletSseServerTransportProvider

File: `mcp-core/src/main/java/io/modelcontextprotocol/server/transport/HttpServletSseServerTransportProvider.java`

```java
public class HttpServletSseServerTransportProvider {
    private final Scheduler dedicatedScheduler;
    
    protected HttpServletSseServerTransportProvider(...) {
        // Crea scheduler dedicato per questo transport
        this.dedicatedScheduler = Schedulers.newBoundedElastic(
            4, Integer.MAX_VALUE, "http-servlet-sse", 60, true);
        
        if (keepAliveInterval != null) {
            this.keepAliveScheduler = KeepAliveScheduler.builder(...)
                .scheduler(dedicatedScheduler)
                .build();
        }
    }
    
    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            isClosing.set(true);
            
            // Stop keep-alive scheduler
            if (keepAliveScheduler != null) {
                keepAliveScheduler.stop();
            }
            
            // Close all sessions
            sessions.values().forEach(session -> {
                try {
                    session.close();
                } catch (Exception e) {
                    logger.error("Error closing session", e);
                }
            });
            sessions.clear();
            
            // Dispose dedicated scheduler
            if (dedicatedScheduler != null && !dedicatedScheduler.isDisposed()) {
                dedicatedScheduler.dispose();
            }
        }).then(Mono.delay(Duration.ofMillis(100))).then();
    }
}
```

## Verifica della Soluzione

### Test da Eseguire

```bash
cd mcp-core
mvn test -Dtest=MinimalThreadLeakReproductionTest
```

### Risultati Attesi

1. **Test 1**: Nessun warning di thread leak
2. **Test 2**: Nessun warning di thread leak
3. **Test 3**: Nessun warning di thread leak
4. Log mostra thread con nomi dedicati: `http-sse-client-X`, `http-servlet-sse-X`, `keep-alive-X`

### Verifica Completa

```bash
# Test completo della suite SSE
mvn test -Dtest=HttpServletSseIntegrationTests

# Verifica che non ci siano thread leak
grep -i "thread leak" target/surefire-reports/*.txt
```

## Impatto e Considerazioni

### Vantaggi

1. ✅ Elimina thread leak in test e produzione
2. ✅ Migliore isolamento tra componenti
3. ✅ Cleanup deterministico delle risorse
4. ✅ Nomi thread più descrittivi per debugging

### Svantaggi

1. ⚠️ Leggero overhead per creazione scheduler dedicati
2. ⚠️ Richiede modifiche in più file
3. ⚠️ Necessita testing approfondito

### Backward Compatibility

- ✅ API pubblica non cambia
- ✅ Comportamento funzionale identico
- ✅ Solo implementazione interna modificata

## File Modificati

1. ✅ `mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java`
2. ⏳ `mcp-core/src/main/java/io/modelcontextprotocol/util/KeepAliveScheduler.java`
3. ⏳ `mcp-core/src/main/java/io/modelcontextprotocol/server/transport/HttpServletSseServerTransportProvider.java`

## Conclusione

La soluzione completa richiede:
1. ✅ Client-side: COMPLETATO - scheduler dedicato in HttpClientSseClientTransport
2. ⏳ Server-side: DA IMPLEMENTARE - scheduler dedicato in HttpServletSseServerTransportProvider e KeepAliveScheduler

Una volta implementate entrambe le parti, il thread leak sarà completamente risolto.