# Thread Leak Analysis - HttpServletSseIntegrationTests

## Problem Description

During the execution of [`HttpServletSseIntegrationTests`](mcp-core/src/test/java/io/modelcontextprotocol/server/HttpServletSseIntegrationTests.java), Tomcat logs warnings about threads that were not properly stopped:

```
AVVERTENZA: The web application [ROOT] appears to have started a thread named [parallel-3] 
but has failed to stop it. This is very likely to create a memory leak.
Stack trace of thread:
 sun.misc.Unsafe.park(Native Method)
 java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:215)
 java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitNanos(...)
 java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take(...)
```

## Root Cause Analysis

### Thread Origin
The threads `parallel-3` and `parallel-4` are created by **Project Reactor's parallel scheduler** (`Schedulers.parallel()`). These threads are used for:
- SSE (Server-Sent Events) stream processing
- Async HTTP client operations
- Reactive pipeline execution

### Lifecycle Issue
The problem occurs because:

1. **Reactor schedulers are global** - They persist across test executions
2. **Tomcat stops first** - The `@AfterEach` method stops Tomcat before shutting down Reactor schedulers
3. **Timing mismatch** - `Schedulers.shutdownNow()` in `@AfterAll` happens too late
4. **Tomcat's classloader check** - When Tomcat stops, it checks for threads started by its classloader and warns about any still running

### Execution Flow
```
@BeforeEach
  ├─ Start Tomcat
  ├─ Create SSE transport (uses Reactor)
  └─ Reactor creates parallel-3, parallel-4 threads

@Test
  └─ Perform SSE handshake (threads are active)

@AfterEach
  ├─ Close client
  ├─ Close transport
  ├─ Close server
  └─ Stop Tomcat ← ⚠️ WARNING: parallel-3, parallel-4 still running!

@AfterAll
  └─ Schedulers.shutdownNow() ← Too late, Tomcat already complained
```

## Minimal Reproduction Test

A minimal test case has been created: [`MinimalThreadLeakReproductionTest.java`](mcp-core/src/test/java/io/modelcontextprotocol/server/MinimalThreadLeakReproductionTest.java)

This test class contains three test methods to analyze different cleanup strategies:

### Test 1: Standard Cleanup (Reproduces the Issue)
```java
@Test
@Order(1)
void testStandardCleanup_ReproducesThreadLeak()
```
- Performs SSE handshake
- Uses standard cleanup order (client → server → Tomcat)
- **Expected**: Thread leak warning appears

### Test 2: Early Scheduler Shutdown
```java
@Test
@Order(2)
void testEarlySchedulerShutdown_MayPreventThreadLeak()
```
- Performs SSE handshake
- Closes client resources
- **Shuts down Reactor schedulers BEFORE stopping Tomcat**
- **Expected**: Thread leak warning should not appear (or be reduced)

### Test 3: With Delays
```java
@Test
@Order(3)
void testWithDelays_AllowAsyncCompletion()
```
- Performs SSE handshake
- Adds 1-second delay before cleanup
- Allows async operations to complete
- **Expected**: Analyze if delay helps reduce thread leak

## Running the Minimal Test

### Execute the test:
```bash
mvn test -Dtest=MinimalThreadLeakReproductionTest
```

### Analyze the output:
1. Check console output for detailed logging
2. Look for thread leak warnings in the logs
3. Compare behavior across the three test methods
4. Check `target/surefire-reports/` for detailed logs

### Expected Output Structure:
```
=== SETUP START ===
Using port: 12345
Tomcat started successfully
Server is ready
=== SETUP COMPLETE ===

=== TEST 1: Standard Cleanup (Reproduces Thread Leak) ===
Creating client transport...
Creating MCP sync client...
Initializing client (performing handshake)...
Handshake completed successfully
Test completed - cleanup will follow in @AfterEach
EXPECTED: Thread leak warning should appear in logs

=== CLEANUP START ===
Closing client...
Closing client transport...
Closing transport provider...
Closing async server...
Stopping Tomcat...
⚠️ AVVERTENZA: The web application [ROOT] appears to have started a thread named [parallel-3]...
=== CLEANUP COMPLETE ===
```

## Potential Solutions

### Solution 1: Early Scheduler Shutdown (Recommended for Tests)
Shut down Reactor schedulers before stopping Tomcat:

```java
@AfterEach
void cleanup() {
    // Close client and transport
    closeClientResources();
    
    // Shutdown schedulers BEFORE stopping Tomcat
    Schedulers.shutdownNow();
    Thread.sleep(500); // Allow threads to terminate
    
    // Now stop Tomcat
    tomcat.stop();
    tomcat.destroy();
}
```

**Pros**: Prevents the warning
**Cons**: Affects global scheduler state, may impact other tests

### Solution 2: Use Bounded Schedulers
Create dedicated schedulers for tests instead of using global ones:

```java
Scheduler testScheduler = Schedulers.newParallel("test-parallel", 4);
// Use testScheduler for operations
// Dispose in @AfterEach
testScheduler.dispose();
```

**Pros**: Isolated, no global state pollution
**Cons**: Requires code changes in transport implementations

### Solution 3: Increase Tomcat Shutdown Timeout
Give more time for threads to finish naturally:

```java
tomcat.getConnector().setAsyncTimeout(5000);
// Add delay before stop
Thread.sleep(1000);
tomcat.stop();
```

**Pros**: Simple, no architectural changes
**Cons**: Doesn't solve the root cause, just masks it

### Solution 4: Use @DirtiesContext (Spring Tests)
For Spring-based tests, mark context as dirty to force cleanup:

```java
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
```

**Pros**: Spring handles cleanup
**Cons**: Only works with Spring Test framework

## Recommendations

### For Test Code
1. **Use Test 2 approach**: Shut down schedulers before stopping Tomcat in tests
2. **Add explicit waits**: Allow async operations to complete before cleanup
3. **Use `@TestMethodOrder`**: Ensure predictable test execution order
4. **Monitor thread count**: Add assertions to verify thread cleanup

### For Production Code
1. **Use bounded schedulers**: Create dedicated schedulers with proper lifecycle management
2. **Implement proper shutdown hooks**: Ensure schedulers are disposed when application stops
3. **Use try-with-resources**: Where applicable, for automatic resource cleanup
4. **Add metrics**: Monitor thread pool sizes and active threads

## Further Investigation

To analyze the issue more deeply:

1. **Enable thread dumps**:
   ```bash
   jstack <pid> > thread_dump.txt
   ```

2. **Use JVM flags**:
   ```bash
   -XX:+PrintGCDetails -XX:+PrintGCTimeStamps
   ```

3. **Monitor with JConsole/VisualVM**:
   - Connect to test JVM
   - Monitor thread creation/destruction
   - Check for thread leaks

4. **Add debug logging**:
   ```java
   System.setProperty("reactor.schedulers.defaultPoolSize", "2");
   System.setProperty("reactor.trace.operatorStacktrace", "true");
   ```

## References

- [Project Reactor Documentation - Schedulers](https://projectreactor.io/docs/core/release/reference/#schedulers)
- [Tomcat Memory Leak Detection](https://tomcat.apache.org/tomcat-9.0-doc/config/context.html#Context_Parameters)
- [HttpServletSseIntegrationTests](mcp-core/src/test/java/io/modelcontextprotocol/server/HttpServletSseIntegrationTests.java)
- [MinimalThreadLeakReproductionTest](mcp-core/src/test/java/io/modelcontextprotocol/server/MinimalThreadLeakReproductionTest.java)

## Conclusion

The thread leak warning is caused by Reactor's global parallel scheduler threads not being shut down before Tomcat stops. The minimal test case provides three different approaches to analyze and potentially resolve the issue. The recommended solution for test code is to shut down Reactor schedulers before stopping Tomcat, while production code should use bounded schedulers with proper lifecycle management.