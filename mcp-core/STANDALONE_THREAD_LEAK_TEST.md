# Test Standalone per Thread Leak - Client e Server SSE

## Descrizione

Questo documento descrive come eseguire i test standalone per verificare il thread leak nei componenti SSE MCP.

Sono disponibili due test:

### 1. Test Client SSE
Il test [`HttpSseClientThreadLeakStandaloneTest.java`](src/test/java/io/modelcontextprotocol/client/HttpSseClientThreadLeakStandaloneTest.java) è stato progettato per:

1. **Isolare il problema**: Testa solo il componente client SSE
2. **Semplificare il debug**: Usa un server Docker esterno invece di un setup complesso
3. **Verificare la fix**: Controlla che i thread dedicati vengano correttamente rilasciati

## Prerequisiti

- Java 17+
- Maven 3.6+
- Docker in esecuzione sulla macchina locale
- Connessione internet (per scaricare l'immagine Docker del server)

### 2. Test Server SSE
Il test [`HttpSseServerThreadLeakStandaloneTest.java`](src/test/java/io/modelcontextprotocol/server/HttpSseServerThreadLeakStandaloneTest.java) è stato progettato per:

1. **Isolare il problema**: Testa solo il componente server SSE
2. **Semplificare il debug**: Usa Tomcat embedded invece di setup esterni
3. **Verificare la fix**: Controlla che i thread dedicati del server vengano correttamente rilasciati

## Come Eseguire i Test

### Test del Client SSE

#### Opzione 1: Eseguire tutti i test della classe

```bash
cd mcp-core
mvn test -Dtest=HttpSseClientThreadLeakStandaloneTest
```

#### Opzione 2: Eseguire un singolo test

```bash
# Test con connessioni multiple
mvn test -Dtest=HttpSseClientThreadLeakStandaloneTest#testClientThreadLeakWithMultipleConnections

# Test con singolo client
mvn test -Dtest=HttpSseClientThreadLeakStandaloneTest#testSingleClientLifecycle
```

### Test del Server SSE

#### Opzione 1: Eseguire tutti i test della classe

```bash
cd mcp-core
mvn test -Dtest=HttpSseServerThreadLeakStandaloneTest
```

#### Opzione 2: Eseguire un singolo test

```bash
# Test con connessioni multiple
mvn test -Dtest=HttpSseServerThreadLeakStandaloneTest#testServerThreadLeakWithMultipleConnections

# Test con singola connessione
mvn test -Dtest=HttpSseServerThreadLeakStandaloneTest#testSingleSseConnectionLifecycle
```

### Eseguire Entrambi i Test

```bash
# Esegui tutti i test standalone
mvn test -Dtest="*ThreadLeakStandaloneTest"

# Con log dettagliati
mvn test -Dtest="*ThreadLeakStandaloneTest" -X
```

## Cosa Verificano i Test

### Test Client SSE

#### Test 1: `testClientThreadLeakWithMultipleConnections`

Questo test:
1. Conta i thread baseline prima di iniziare
2. Crea e chiude 5 client SSE in sequenza
3. Dopo ogni iterazione, conta i thread attivi
4. Alla fine, verifica che:
   - Non ci siano thread `http-sse-client` ancora attivi
   - L'incremento totale dei thread sia minimo (max 5 thread di tolleranza)

**Output atteso (con fix applicata):**
```
BASELINE: Total threads = 15, HTTP-SSE threads = 0
After iteration 1: Total threads = 16, HTTP-SSE threads = 0
After iteration 2: Total threads = 16, HTTP-SSE threads = 0
...
FINAL: Total threads = 16, HTTP-SSE threads = 0
Thread increase from baseline: 1
=== TEST PASSED: No thread leak detected ===
```

**Output con thread leak (senza fix):**
```
BASELINE: Total threads = 15, HTTP-SSE threads = 0
After iteration 1: Total threads = 19, HTTP-SSE threads = 4
After iteration 2: Total threads = 23, HTTP-SSE threads = 8
...
FINAL: Total threads = 35, HTTP-SSE threads = 20
=== THREAD LEAK DETECTED ===
Found 20 HTTP-SSE threads still alive after all clients closed:
  - http-sse-client-1
  - http-sse-client-2
  ...
```

#### Test 2: `testSingleClientLifecycle`

Questo test verifica il ciclo di vita di un singolo client:
1. Conta i thread prima della creazione del client
2. Crea il client e verifica che i thread `http-sse-client` siano stati creati
3. Chiude il client
4. Verifica che tutti i thread `http-sse-client` siano stati rilasciati

### Test Server SSE

#### Test 1: `testServerThreadLeakWithMultipleConnections`

Questo test:
1. Avvia un server MCP SSE in Tomcat embedded
2. Simula 5 connessioni SSE sequenziali da client HTTP
3. Dopo ogni connessione, conta i thread attivi
4. Alla fine, verifica che:
   - Non ci siano thread `http-sse-server` ancora attivi
   - L'incremento totale dei thread sia minimo

**Output atteso (con fix applicata):**
```
BASELINE: Total threads = 15, HTTP-SSE threads = 0
AFTER STARTUP: Total threads = 25, HTTP-SSE threads = 0
After iteration 1: Total threads = 25, HTTP-SSE threads = 0
After iteration 2: Total threads = 25, HTTP-SSE threads = 0
...
FINAL: Total threads = 25, HTTP-SSE threads = 0
=== TEST PASSED: No thread leak detected ===
```

#### Test 2: `testSingleSseConnectionLifecycle`

Questo test verifica il ciclo di vita di una singola connessione SSE:
1. Avvia il server in Tomcat
2. Apre una connessione SSE e verifica che i thread siano creati
3. Chiude la connessione
4. Verifica che tutti i thread `http-sse-server` siano stati rilasciati

## Interpretazione dei Risultati

### ✅ Test Passato

Se il test passa, significa che:
- I thread dedicati vengono correttamente creati quando il client è attivo
- I thread vengono correttamente rilasciati quando il client viene chiuso
- Non c'è thread leak

### ❌ Test Fallito

Se il test fallisce con un messaggio tipo:
```
AssertionError: No HTTP-SSE client threads should remain after closing all clients
Expected: 0
Actual: 20
```

Significa che c'è un thread leak e i thread non vengono rilasciati correttamente.

## Architettura dei Test

### Test Client SSE

```
┌─────────────────────────────────────────┐
│  Docker Container                       │
│  (mcp-everything-server)                │
│  - Espone endpoint SSE su porta 3001    │
└─────────────────┬───────────────────────┘
                  │ HTTP/SSE
                  │
┌─────────────────▼───────────────────────┐
│  Test Java                              │
│  - Crea HttpClientSseClientTransport    │
│  - Inizializza McpAsyncClient           │
│  - Esegue operazioni                    │
│  - Chiude client                        │
│  - Verifica thread count                │
└─────────────────────────────────────────┘
```

### Test Server SSE

```
┌─────────────────────────────────────────┐
│  Test Java                              │
│  - Avvia Tomcat embedded                │
│  - Deploy HttpServletSseServerTransport │
│  - Crea McpSyncServer                   │
└─────────────────┬───────────────────────┘
                  │ HTTP/SSE
                  │
┌─────────────────▼───────────────────────┐
│  Simulazione Client HTTP                │
│  - Apre connessioni SSE                 │
│  - Legge eventi                         │
│  - Chiude connessioni                   │
│  - Verifica thread count                │
└─────────────────────────────────────────┘
```

## Vantaggi di Questo Approccio

### Test Client SSE
1. **Isolamento**: Testa solo il client, non il server
2. **Semplicità**: Non richiede setup complessi di Tomcat o altri server
3. **Ripetibilità**: Usa Docker per garantire un ambiente consistente
4. **Debug facile**: Log dettagliati per ogni fase del test
5. **Velocità**: Più veloce di test end-to-end completi

### Test Server SSE
1. **Isolamento**: Testa solo il server, non il client
2. **Controllo completo**: Usa Tomcat embedded per controllo totale
3. **Nessuna dipendenza esterna**: Non richiede Docker o servizi esterni
4. **Simulazione realistica**: Simula connessioni HTTP/SSE reali
5. **Debug dettagliato**: Log completi del ciclo di vita delle connessioni

## Troubleshooting

### Test Client SSE

#### Docker non disponibile

Se Docker non è disponibile, il test fallirà con:
```
org.testcontainers.containers.ContainerLaunchException: Container startup failed
```

**Soluzione**: Avvia Docker Desktop o il daemon Docker.

#### Timeout del test

Se il test va in timeout (120 secondi), potrebbe essere dovuto a:
- Rete lenta (download immagine Docker)
- Server che non risponde

**Soluzione**: Aumenta il timeout o verifica la connessione di rete.

### Test Server SSE

#### Porta già in uso

Se il test fallirà con:
```
java.net.BindException: Address already in use
```

**Soluzione**: Il test usa `TomcatTestUtil.findAvailablePort()` per trovare una porta libera automaticamente.

#### Tomcat non si avvia

Se Tomcat non si avvia correttamente:
```
org.apache.catalina.LifecycleException: Failed to start component
```

**Soluzione**: Verifica che non ci siano conflitti di classpath o altre istanze di Tomcat in esecuzione.

### Falsi positivi

A volte il garbage collector potrebbe non aver ancora rilasciato i thread. Il test include:
- `System.gc()` per forzare la garbage collection
- Sleep di 2 secondi per permettere la pulizia
- Tolleranza di 5 thread per variazioni normali della JVM

## Prossimi Passi

### Se il Test Client rileva un thread leak:

1. Verifica che la fix sia stata applicata correttamente in [`HttpClientSseClientTransport.java`](src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java)
2. Controlla che il `dedicatedScheduler` sia:
   - Inizializzato nel costruttore (linea 82)
   - Usato in tutti i `.subscribeOn()` (linee 271, 320, 355)
   - Disposed in `closeGracefully()` (linee 364-366)
3. Esegui il test con `-X` per vedere i log dettagliati
4. Usa un profiler (VisualVM, JProfiler) per analizzare i thread in dettaglio

### Se il Test Server rileva un thread leak:

1. Verifica che la fix sia stata applicata correttamente in [`HttpServletSseServerTransportProvider.java`](src/main/java/io/modelcontextprotocol/server/transport/HttpServletSseServerTransportProvider.java)
2. Controlla che il `dedicatedScheduler` sia:
   - Inizializzato nel costruttore
   - Usato per tutte le operazioni async
   - Disposed nel metodo di cleanup
3. Verifica che le connessioni SSE vengano chiuse correttamente
4. Controlla i log di Tomcat per errori di shutdown

## Test Alternativi

Se preferisci testare con un server Tomcat locale invece di Docker, puoi:

1. Deployare il server MCP in Tomcat
2. Modificare `serverUrl` nel test per puntare a `http://localhost:8080`
3. Commentare le sezioni `@BeforeAll` e `@AfterAll` che gestiscono il container Docker

Esempio:
```java
// private static String serverUrl = "http://" + host + ":" + port;
private static String serverUrl = "http://localhost:8080"; // Tomcat locale