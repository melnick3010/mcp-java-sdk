# Guida Completa al Testing del Thread Leak

## Panoramica

Questa guida descrive l'approccio semplificato per testare e verificare la fix del thread leak nei componenti SSE (Server-Sent Events) del MCP Java SDK.

## Problema Originale

Il problema del thread leak si verificava perché:
- I componenti client e server SSE utilizzavano scheduler Reactor globali
- Questi scheduler non venivano mai rilasciati quando i transport venivano chiusi
- Ogni istanza di client/server creava nuovi thread che rimanevano attivi indefinitamente

## Soluzione Implementata

La fix introduce **scheduler dedicati** per ogni istanza di transport:

### Client SSE ([`HttpClientSseClientTransport.java`](src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java))

```java
// Scheduler dedicato creato nel costruttore (linea 82)
this.dedicatedScheduler = Schedulers.newBoundedElastic(4, Integer.MAX_VALUE, "http-sse-client", 60, true);

// Usato in tutte le operazioni async (linee 271, 320, 355)
.subscribeOn(dedicatedScheduler)

// Rilasciato in closeGracefully() (linee 364-366)
if (dedicatedScheduler != null && !dedicatedScheduler.isDisposed()) {
    dedicatedScheduler.dispose();
}
```

### Server SSE ([`HttpServletSseServerTransportProvider.java`](src/main/java/io/modelcontextprotocol/server/transport/HttpServletSseServerTransportProvider.java))

Implementazione analoga con scheduler dedicato per il server.

## Approccio di Testing Semplificato

### Perché Test Standalone?

I test standalone offrono diversi vantaggi:

1. **Isolamento**: Testano un solo componente alla volta (client O server)
2. **Semplicità**: Setup minimo, facile da eseguire e debuggare
3. **Velocità**: Più veloci dei test end-to-end completi
4. **Chiarezza**: Risultati immediati e facili da interpretare
5. **Indipendenza**: Non richiedono setup complessi o dipendenze esterne complicate

### Test Disponibili

#### 1. Test Client SSE
**File**: [`HttpSseClientThreadLeakStandaloneTest.java`](src/test/java/io/modelcontextprotocol/client/HttpSseClientThreadLeakStandaloneTest.java)

**Cosa fa**:
- Avvia un server MCP SSE in un container Docker
- Crea e chiude ripetutamente client SSE (5 iterazioni)
- Monitora il conteggio dei thread dopo ogni iterazione
- Verifica che i thread `http-sse-client` vengano rilasciati

**Prerequisiti**:
- Docker in esecuzione
- Connessione internet (per scaricare l'immagine Docker)

**Come eseguire**:
```bash
# Linux/Mac
cd mcp-core
./run-thread-leak-tests.sh client

# Windows
cd mcp-core
run-thread-leak-tests.bat client

# Oppure con Maven direttamente
mvn test -Dtest=HttpSseClientThreadLeakStandaloneTest
```

#### 2. Test Server SSE
**File**: [`HttpSseServerThreadLeakStandaloneTest.java`](src/test/java/io/modelcontextprotocol/server/HttpSseServerThreadLeakStandaloneTest.java)

**Cosa fa**:
- Avvia un server MCP SSE in Tomcat embedded
- Simula connessioni SSE multiple da client HTTP
- Monitora il conteggio dei thread dopo ogni connessione
- Verifica che i thread `http-sse-server` vengano rilasciati

**Prerequisiti**:
- Nessuna dipendenza esterna (tutto embedded)

**Come eseguire**:
```bash
# Linux/Mac
cd mcp-core
./run-thread-leak-tests.sh server

# Windows
cd mcp-core
run-thread-leak-tests.bat server

# Oppure con Maven direttamente
mvn test -Dtest=HttpSseServerThreadLeakStandaloneTest
```

#### 3. Test Completo (Client + Server)
**Come eseguire**:
```bash
# Linux/Mac
cd mcp-core
./run-thread-leak-tests.sh both

# Windows
cd mcp-core
run-thread-leak-tests.bat both

# Oppure con Maven direttamente
mvn test -Dtest="*ThreadLeakStandaloneTest"
```

## Interpretazione dei Risultati

### ✅ Test Passato (Con Fix)

Output atteso:
```
BASELINE: Total threads = 15, HTTP-SSE threads = 0
After iteration 1: Total threads = 16, HTTP-SSE threads = 0
After iteration 2: Total threads = 16, HTTP-SSE threads = 0
After iteration 3: Total threads = 16, HTTP-SSE threads = 0
After iteration 4: Total threads = 16, HTTP-SSE threads = 0
After iteration 5: Total threads = 16, HTTP-SSE threads = 0
FINAL: Total threads = 16, HTTP-SSE threads = 0
Thread increase from baseline: 1

=== TEST PASSED: No thread leak detected ===
```

**Significato**: I thread dedicati vengono correttamente creati e rilasciati. Nessun leak.

### ❌ Test Fallito (Senza Fix)

Output con thread leak:
```
BASELINE: Total threads = 15, HTTP-SSE threads = 0
After iteration 1: Total threads = 19, HTTP-SSE threads = 4
After iteration 2: Total threads = 23, HTTP-SSE threads = 8
After iteration 3: Total threads = 27, HTTP-SSE threads = 12
After iteration 4: Total threads = 31, HTTP-SSE threads = 16
After iteration 5: Total threads = 35, HTTP-SSE threads = 20
FINAL: Total threads = 35, HTTP-SSE threads = 20

=== THREAD LEAK DETECTED ===
Found 20 HTTP-SSE threads still alive after all clients closed:
  - http-sse-client-1
  - http-sse-client-2
  - http-sse-client-3
  ...
```

**Significato**: I thread non vengono rilasciati. C'è un leak evidente.

## Vantaggi dell'Approccio Standalone

### Rispetto ai Test End-to-End Completi

| Aspetto | Test Standalone | Test End-to-End |
|---------|----------------|-----------------|
| **Setup** | Minimo (Docker o Tomcat embedded) | Complesso (server + client + infrastruttura) |
| **Velocità** | Veloce (30-60 secondi) | Lento (2-5 minuti) |
| **Debug** | Facile (un solo componente) | Difficile (molti componenti) |
| **Isolamento** | Completo | Parziale |
| **Manutenzione** | Semplice | Complessa |
| **Risultati** | Chiari e immediati | Possono essere ambigui |

### Rispetto al Deployment in Tomcat Esterno

| Aspetto | Test Standalone | Tomcat Esterno |
|---------|----------------|----------------|
| **Setup** | Automatico | Manuale |
| **Ripetibilità** | Alta | Bassa |
| **Automazione** | Completa | Parziale |
| **CI/CD** | Facile da integrare | Difficile |
| **Pulizia** | Automatica | Manuale |

## Workflow Consigliato

### 1. Sviluppo Locale

Durante lo sviluppo:
```bash
# Test rapido del componente su cui stai lavorando
./run-thread-leak-tests.sh client  # se lavori sul client
./run-thread-leak-tests.sh server  # se lavori sul server
```

### 2. Prima del Commit

Prima di committare le modifiche:
```bash
# Test completo
./run-thread-leak-tests.sh both
```

### 3. CI/CD Pipeline

Nel pipeline CI/CD:
```yaml
# Esempio GitHub Actions
- name: Run Thread Leak Tests
  run: |
    cd mcp-core
    mvn test -Dtest="*ThreadLeakStandaloneTest"
```

### 4. Debug di un Problema

Se rilevi un thread leak:

1. **Esegui il test con log dettagliati**:
   ```bash
   mvn test -Dtest=HttpSseClientThreadLeakStandaloneTest -X
   ```

2. **Analizza i thread attivi**:
   - I log mostrano i nomi dei thread che non sono stati rilasciati
   - Cerca pattern come `http-sse-client-N` o `http-sse-server-N`

3. **Verifica la fix**:
   - Controlla che lo scheduler dedicato sia inizializzato
   - Verifica che sia usato in tutti i `.subscribeOn()`
   - Assicurati che sia disposed in `closeGracefully()`

4. **Usa un profiler** (opzionale):
   - VisualVM
   - JProfiler
   - YourKit

## Documentazione Aggiuntiva

- **[STANDALONE_THREAD_LEAK_TEST.md](STANDALONE_THREAD_LEAK_TEST.md)**: Documentazione dettagliata dei test
- **[THREAD_LEAK_SOLUTION.md](THREAD_LEAK_SOLUTION.md)**: Descrizione della soluzione implementata
- **[THREAD_LEAK_FINAL_REPORT.md](THREAD_LEAK_FINAL_REPORT.md)**: Report finale dell'analisi

## Script di Utilità

### Linux/Mac: `run-thread-leak-tests.sh`
```bash
./run-thread-leak-tests.sh [client|server|both|help]
```

### Windows: `run-thread-leak-tests.bat`
```cmd
run-thread-leak-tests.bat [client|server|both|help]
```

Entrambi gli script:
- Verificano i prerequisiti (Java, Maven, Docker)
- Eseguono i test appropriati
- Forniscono output colorato e chiaro
- Gestiscono gli errori in modo appropriato

## Conclusioni

L'approccio standalone per il testing del thread leak offre:

✅ **Semplicità**: Setup minimo, esecuzione rapida  
✅ **Chiarezza**: Risultati immediati e facili da interpretare  
✅ **Efficacia**: Rileva i thread leak in modo affidabile  
✅ **Manutenibilità**: Facile da mantenere e aggiornare  
✅ **Automazione**: Perfetto per CI/CD  

Questo approccio è ideale per:
- Sviluppo quotidiano
- Verifica delle fix
- Regressione testing
- CI/CD automation

Per test più complessi o scenari end-to-end, i test esistenti rimangono disponibili e complementari a questi test standalone.