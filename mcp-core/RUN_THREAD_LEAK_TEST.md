# Quick Guide: Running Thread Leak Analysis Test

## Quick Start

### Run the minimal test:
```bash
cd mcp-core
mvn test -Dtest=MinimalThreadLeakReproductionTest
```

### View detailed output:
```bash
# Console output
mvn test -Dtest=MinimalThreadLeakReproductionTest 2>&1 | tee thread_leak_test.log

# Surefire reports
cat target/surefire-reports/io.modelcontextprotocol.server.MinimalThreadLeakReproductionTest-output.txt
```

## What to Look For

### 1. Thread Leak Warnings
Search for this pattern in the output:
```
AVVERTENZA: The web application [ROOT] appears to have started a thread named [parallel-X]
```

### 2. Test Results Comparison

**Test 1 (Standard Cleanup)**:
- ✅ Should show thread leak warning
- Purpose: Confirms the issue exists

**Test 2 (Early Scheduler Shutdown)**:
- ❓ Should NOT show thread leak warning (or reduced)
- Purpose: Tests if early shutdown prevents the leak

**Test 3 (With Delays)**:
- ❓ May or may not show warning
- Purpose: Tests if timing is a factor

## Analysis Checklist

- [ ] Test 1 reproduces the thread leak warning
- [ ] Test 2 prevents or reduces the warning
- [ ] Test 3 shows timing impact
- [ ] All three tests complete successfully
- [ ] No other errors in the logs

## Expected Output Pattern

```
=== TEST 1: Standard Cleanup (Reproduces Thread Leak) ===
...
⚠️ AVVERTENZA: thread named [parallel-3] ... memory leak

=== TEST 2: Early Scheduler Shutdown ===
...
✅ No thread leak warning (or reduced)

=== TEST 3: With Delays for Async Completion ===
...
❓ Check if delay helps
```

## Troubleshooting

### If tests fail to start:
```bash
# Check if port is available
netstat -an | grep LISTEN

# Clean and rebuild
mvn clean test -Dtest=MinimalThreadLeakReproductionTest
```

### If tests hang:
- Check timeout settings (default: 60 seconds per test)
- Look for deadlocks in thread dumps
- Verify Tomcat starts correctly

### If no warnings appear:
- Check log level configuration
- Verify Tomcat version (should be 9.0.87)
- Ensure Reactor is being used

## Next Steps

Based on test results:

1. **If Test 2 prevents the warning**:
   - Apply early scheduler shutdown to main test class
   - Document the solution
   - Consider architectural improvements

2. **If Test 2 doesn't help**:
   - Investigate other cleanup strategies
   - Check for additional thread sources
   - Consider using bounded schedulers

3. **If Test 3 shows timing matters**:
   - Add appropriate delays in cleanup
   - Investigate async operation completion
   - Consider using CompletableFuture.allOf()

## Related Files

- Test class: [`MinimalThreadLeakReproductionTest.java`](src/test/java/io/modelcontextprotocol/server/MinimalThreadLeakReproductionTest.java)
- Analysis document: [`THREAD_LEAK_ANALYSIS.md`](THREAD_LEAK_ANALYSIS.md)
- Original test: [`HttpServletSseIntegrationTests.java`](src/test/java/io/modelcontextprotocol/server/HttpServletSseIntegrationTests.java)
- Log output: [`target/surefire-reports/io.modelcontextprotocol.server.HttpServletSseIntegrationTests-output.txt`](target/surefire-reports/io.modelcontextprotocol.server.HttpServletSseIntegrationTests-output.txt)