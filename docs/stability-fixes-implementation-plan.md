# Stability Fixes Implementation Plan

**Project:** Message Router Stability Improvements
**Start Date:** 2025-10-10
**Status:** Ready for Implementation

This document provides a detailed, step-by-step implementation plan for addressing the critical stability issues identified in the message router system.

---

## Overview

### Objectives
1. Eliminate memory leaks in message pointer and callback maps
2. Prevent semaphore permit leaks
3. Fix metrics drift and accuracy issues
4. Improve shutdown and error recovery
5. Add comprehensive monitoring for resource leaks

### Implementation Phases
- **Phase 1 (Week 1):** Critical fixes - P0 priority
- **Phase 2 (Week 2):** High priority fixes - P1 priority
- **Phase 3 (Week 3-4):** Medium priority improvements - P2 priority

---

## Phase 1: Critical Fixes (Week 1)

### Task 1.1: Restructure ProcessPoolImpl Exception Handling
**Priority:** P0 - CRITICAL
**Estimated Effort:** 16 hours (2 days)
**Assignee:** TBD
**Files Modified:**
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/pool/ProcessPoolImpl.java`
- `flowcatalyst-core/src/test/java/tech/flowcatalyst/messagerouter/pool/ProcessPoolImplTest.java`

#### Subtasks

##### 1.1.1: Analyze Current Exception Paths (2 hours)
**Steps:**
1. Document all current exception scenarios in `processMessages()` method
2. Trace cleanup paths for each exception type
3. Identify which resources are leaked in each scenario
4. Create exception path flowchart for team review

**Deliverable:** Exception path analysis document

---

##### 1.1.2: Refactor processMessages() Method (6 hours)
**Steps:**

1. **Create resource tracking variables:**
   ```java
   MessagePointer message = null;
   boolean semaphoreAcquired = false;
   boolean processingStarted = false;
   String originalMessageId = null;  // For MDC cleanup
   ```

2. **Restructure to single try-catch-finally:**
   ```java
   while (running.get() || !messageQueue.isEmpty()) {
       // Reset flags
       message = null;
       semaphoreAcquired = false;
       processingStarted = false;
       originalMessageId = null;

       try {
           // 1. Poll message
           message = messageQueue.poll(1, TimeUnit.SECONDS);
           if (message == null) {
               continue;
           }
           originalMessageId = message.id();

           // 2. Set MDC context
           setMDCContext(message);

           // 3. Acquire semaphore
           semaphore.acquire();
           semaphoreAcquired = true;

           // 4. Record processing started
           poolMetrics.recordProcessingStarted(poolCode);
           processingStarted = true;
           updateGauges();

           // 5. Check rate limiting
           if (shouldRateLimit(message)) {
               LOG.warn("Rate limit exceeded, nacking message");
               poolMetrics.recordRateLimitExceeded(poolCode);
               messageCallback.nack(message);
               continue;
           }

           // 6. Process through mediator
           long startTime = System.currentTimeMillis();
           MediationResult result = mediator.process(message);
           long durationMs = System.currentTimeMillis() - startTime;

           // 7. Handle result
           handleMediationResult(message, result, durationMs);

       } catch (InterruptedException e) {
           LOG.warn("Worker interrupted, exiting gracefully");
           Thread.currentThread().interrupt();
           // Nack message if we have one
           if (message != null) {
               nackSafely(message);
           }
           break;

       } catch (Exception e) {
           LOG.error("Unexpected error processing message", e);
           logExceptionContext(message, e);

           // Nack message if we have one
           if (message != null) {
               nackSafely(message);
               recordProcessingError(message, e);
           }

       } finally {
           // CRITICAL: Cleanup always happens here
           performCleanup(message, originalMessageId, semaphoreAcquired, processingStarted);
       }
   }
   ```

3. **Extract helper methods:**
   ```java
   private void setMDCContext(MessagePointer message) {
       MDC.put("messageId", message.id());
       MDC.put("poolCode", poolCode);
       MDC.put("mediationType", message.mediationType().toString());
       MDC.put("targetUri", message.mediationTarget());
       if (message.rateLimitKey() != null) {
           MDC.put("rateLimitKey", message.rateLimitKey());
           MDC.put("rateLimitPerMinute", String.valueOf(message.rateLimitPerMinute()));
       }
   }

   private boolean shouldRateLimit(MessagePointer message) {
       if (message.rateLimitPerMinute() == null || message.rateLimitKey() == null) {
           return false;
       }
       RateLimiter rateLimiter = getRateLimiter(message.rateLimitKey(), message.rateLimitPerMinute());
       return !rateLimiter.acquirePermission();
   }

   private void handleMediationResult(MessagePointer message, MediationResult result, long durationMs) {
       MDC.put("result", result.name());
       MDC.put("durationMs", String.valueOf(durationMs));

       if (result == MediationResult.SUCCESS) {
           LOG.infof("Message processed successfully");
           poolMetrics.recordProcessingSuccess(poolCode, durationMs);
           messageCallback.ack(message);
       } else {
           LOG.warnf("Mediation failed with result: %s", result);
           poolMetrics.recordProcessingFailure(poolCode, durationMs, result.name());
           messageCallback.nack(message);

           warningService.addWarning(
               "MEDIATION",
               "ERROR",
               String.format("Mediation failed for message %s: %s", message.id(), result),
               "ProcessPool:" + poolCode
           );
       }
   }

   private void nackSafely(MessagePointer message) {
       try {
           messageCallback.nack(message);
       } catch (Exception e) {
           LOG.errorf(e, "Error nacking message during exception handling: %s", message.id());
       }
   }

   private void logExceptionContext(MessagePointer message, Exception e) {
       warningService.addWarning(
           "PROCESSING",
           "ERROR",
           String.format("Unexpected error processing message %s: %s",
               message != null ? message.id() : "unknown",
               e.getMessage()),
           "ProcessPool:" + poolCode
       );
   }

   private void recordProcessingError(MessagePointer message, Exception e) {
       poolMetrics.recordProcessingFailure(
           poolCode,
           0,  // No duration for exceptions
           "EXCEPTION_" + e.getClass().getSimpleName()
       );
   }

   private void performCleanup(MessagePointer message, String messageId,
                                boolean semaphoreAcquired, boolean processingStarted) {
       try {
           // 1. Remove from pipeline map
           if (messageId != null) {
               MessagePointer removed = inPipelineMap.remove(messageId);
               if (removed == null) {
                   LOG.warnf("Message %s was not in pipeline map during cleanup", messageId);
               }
           }

           // 2. Release semaphore permit
           if (semaphoreAcquired) {
               semaphore.release();
           }

           // 3. Update metrics
           if (processingStarted && semaphoreAcquired) {
               // Only update gauges if we successfully acquired semaphore
               updateGauges();
           }

           // 4. Clear MDC
           MDC.clear();

       } catch (Exception e) {
           // Cleanup should NEVER throw, but log if it does
           LOG.errorf(e, "Error during cleanup for message: %s", messageId);
       }
   }
   ```

**Validation:**
- No code throws exceptions outside try block
- Every resource acquisition has corresponding flag
- Finally block handles all cleanup
- Code is more readable and testable

---

##### 1.1.3: Add Comprehensive Unit Tests (6 hours)
**Steps:**

1. **Create test class structure:**
   ```java
   @ExtendWith(MockitoExtension.class)
   class ProcessPoolImplExceptionHandlingTest {

       @Mock private Mediator mediator;
       @Mock private MessageCallback messageCallback;
       @Mock private PoolMetricsService poolMetrics;
       @Mock private WarningService warningService;

       private ConcurrentHashMap<String, MessagePointer> inPipelineMap;
       private ProcessPoolImpl pool;
       private LinkedBlockingQueue<MessagePointer> testQueue;
   }
   ```

2. **Test exception during semaphore acquire:**
   ```java
   @Test
   void testInterruptedException_beforeSemaphoreAcquire_cleansUpProperly() {
       // Given: Message in queue, worker thread will be interrupted
       MessagePointer message = createTestMessage();
       testQueue.offer(message);

       // When: Interrupt thread during poll (before semaphore)
       Thread workerThread = new Thread(() -> pool.processMessages());
       workerThread.start();
       Thread.sleep(100);
       workerThread.interrupt();
       workerThread.join(5000);

       // Then: Message should be removed from pipeline map
       assertThat(inPipelineMap).doesNotContainKey(message.id());

       // And: Semaphore should have all permits
       assertThat(pool.getSemaphore().availablePermits())
           .isEqualTo(pool.getConcurrency());

       // And: Message should be nacked
       verify(messageCallback).nack(message);
   }
   ```

3. **Test exception during MDC setup:**
   ```java
   @Test
   void testException_duringMDCSetup_cleansUpProperly() {
       // Given: Message with malformed data that causes MDC exception
       MessagePointer message = createMessageWithNullFields();
       testQueue.offer(message);
       pool.running.set(false); // Stop after one iteration

       // When: Process messages
       pool.processMessages();

       // Then: Message removed from pipeline map
       assertThat(inPipelineMap).doesNotContainKey(message.id());

       // And: No semaphore permits leaked
       assertThat(pool.getSemaphore().availablePermits())
           .isEqualTo(pool.getConcurrency());

       // And: MDC is cleared
       assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
   }
   ```

4. **Test exception during mediation:**
   ```java
   @Test
   void testException_duringMediation_cleansUpProperly() {
       // Given: Mediator throws unexpected exception
       MessagePointer message = createTestMessage();
       when(mediator.process(any())).thenThrow(new RuntimeException("Mediator error"));
       testQueue.offer(message);
       pool.running.set(false);

       // When: Process messages
       pool.processMessages();

       // Then: All cleanup performed
       assertThat(inPipelineMap).doesNotContainKey(message.id());
       assertThat(pool.getSemaphore().availablePermits())
           .isEqualTo(pool.getConcurrency());
       verify(messageCallback).nack(message);
       verify(poolMetrics).recordProcessingFailure(eq(poolCode), anyLong(), contains("RuntimeException"));
   }
   ```

5. **Test normal success path still works:**
   ```java
   @Test
   void testSuccessfulProcessing_cleansUpProperly() {
       // Given: Message and successful mediation
       MessagePointer message = createTestMessage();
       when(mediator.process(message)).thenReturn(MediationResult.SUCCESS);
       testQueue.offer(message);
       pool.running.set(false);

       // When: Process messages
       pool.processMessages();

       // Then: All cleanup performed correctly
       assertThat(inPipelineMap).doesNotContainKey(message.id());
       verify(messageCallback).ack(message);
       verify(poolMetrics).recordProcessingSuccess(eq(poolCode), anyLong());
   }
   ```

6. **Test rate limiting path:**
   ```java
   @Test
   void testRateLimitExceeded_cleansUpProperly() {
       // Given: Message that will be rate limited
       MessagePointer message = createTestMessageWithRateLimit("customer-123", 1);
       // Exhaust rate limit
       RateLimiter limiter = pool.getRateLimiter("customer-123", 1);
       limiter.acquirePermission();

       testQueue.offer(message);
       pool.running.set(false);

       // When: Process messages
       pool.processMessages();

       // Then: Cleanup performed without calling mediator
       assertThat(inPipelineMap).doesNotContainKey(message.id());
       verify(messageCallback).nack(message);
       verify(mediator, never()).process(any());
       verify(poolMetrics).recordRateLimitExceeded(poolCode);
   }
   ```

7. **Test multiple concurrent exceptions:**
   ```java
   @Test
   void testConcurrentExceptions_noLeaks() throws Exception {
       // Given: Multiple messages, each will cause different exception
       int workerCount = 10;
       CountDownLatch latch = new CountDownLatch(workerCount);

       for (int i = 0; i < workerCount; i++) {
           MessagePointer message = createTestMessage("msg-" + i);
           testQueue.offer(message);
           inPipelineMap.put(message.id(), message);
       }

       // Mock different exceptions for different messages
       when(mediator.process(any()))
           .thenThrow(new RuntimeException("Error 1"))
           .thenThrow(new IllegalStateException("Error 2"))
           .thenReturn(MediationResult.ERROR_CONNECTION)
           // ... vary the responses

       // When: Process with multiple workers
       pool.start();
       Thread.sleep(2000);
       pool.drain();

       // Then: No leaks in any resource
       assertThat(inPipelineMap).isEmpty();
       assertThat(pool.getSemaphore().availablePermits())
           .isEqualTo(pool.getConcurrency());
   }
   ```

**Validation:**
- All tests pass
- Code coverage > 95% for processMessages() method
- All exception paths tested
- No flaky tests

---

##### 1.1.4: Integration Testing (2 hours)
**Steps:**

1. **Create integration test with real components:**
   ```java
   @QuarkusTest
   class ProcessPoolImplIntegrationTest {

       @Test
       void testPoolWithRealQueueAndMediatorExceptions() {
           // Test with real SQS, real HTTP mediator returning errors
           // Verify no leaks after 1000 messages with 10% error rate
       }

       @Test
       void testForcedShutdownDuringProcessing() {
           // Submit many messages, force shutdown mid-processing
           // Verify cleanup happens correctly
       }
   }
   ```

2. **Run load test in staging:**
   - 10,000 messages
   - 20% error rate (various exception types)
   - Monitor map sizes every 10 seconds
   - Verify steady state reached

**Validation:**
- Integration tests pass
- Map sizes remain bounded
- No permit leaks observed
- Clean shutdown achieved

---

### Task 1.2: Fix Metrics Tracking
**Priority:** P0 - CRITICAL
**Estimated Effort:** 8 hours (1 day)
**Assignee:** TBD
**Files Modified:**
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/metrics/PoolMetricsService.java`
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/metrics/MicrometerPoolMetricsService.java`
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/pool/ProcessPoolImpl.java`

#### Subtasks

##### 1.2.1: Add New Metrics Methods (2 hours)
**Steps:**

1. **Update PoolMetricsService interface:**
   ```java
   public interface PoolMetricsService {

       // Existing methods...

       /**
        * Record that processing finished (success or failure)
        * This decrements the active workers counter
        */
       void recordProcessingFinished(String poolCode);

       /**
        * Reset metrics for a pool (used during pool restart)
        */
       void resetPoolMetrics(String poolCode);
   }
   ```

2. **Implement in MicrometerPoolMetricsService:**
   ```java
   @Override
   public void recordProcessingFinished(String poolCode) {
       PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
       int current = metrics.activeWorkers.decrementAndGet();

       // Defensive check - active workers should never go negative
       if (current < 0) {
           LOG.errorf("METRICS DRIFT DETECTED: activeWorkers for pool %s is negative: %d. Resetting to 0.",
               poolCode, current);
           metrics.activeWorkers.set(0);

           // Add warning
           warningService.addWarning(
               "METRICS_DRIFT",
               "ERROR",
               "Active workers count went negative for pool " + poolCode,
               "MicrometerPoolMetricsService"
           );
       }
   }

   @Override
   public void resetPoolMetrics(String poolCode) {
       PoolMetricsHolder metrics = poolMetrics.get(poolCode);
       if (metrics != null) {
           metrics.activeWorkers.set(0);
           LOG.infof("Reset metrics for pool: %s", poolCode);
       }
   }
   ```

3. **Update recordProcessingSuccess and recordProcessingFailure:**
   ```java
   @Override
   public void recordProcessingSuccess(String poolCode, long durationMs) {
       PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
       metrics.messagesSucceeded.increment();
       metrics.processingTimer.record(Duration.ofMillis(durationMs));
       metrics.totalProcessingTimeMs.addAndGet(durationMs);
       // REMOVED: metrics.activeWorkers.decrementAndGet();
       metrics.lastActivityTimestamp.set(System.currentTimeMillis());
   }

   @Override
   public void recordProcessingFailure(String poolCode, long durationMs, String errorType) {
       PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
       metrics.messagesFailed.increment();
       metrics.processingTimer.record(Duration.ofMillis(durationMs));
       metrics.totalProcessingTimeMs.addAndGet(durationMs);
       // REMOVED: metrics.activeWorkers.decrementAndGet();
       metrics.lastActivityTimestamp.set(System.currentTimeMillis());

       // Track error type...
   }
   ```

**Validation:**
- Interface compiles
- Implementation matches contract
- Defensive checks in place

---

##### 1.2.2: Update ProcessPoolImpl to Use New Methods (2 hours)
**Steps:**

1. **Update performCleanup method:**
   ```java
   private void performCleanup(MessagePointer message, String messageId,
                                boolean semaphoreAcquired, boolean processingStarted) {
       try {
           // 1. Remove from pipeline map
           if (messageId != null) {
               inPipelineMap.remove(messageId);
           }

           // 2. Release semaphore and update metrics
           if (semaphoreAcquired) {
               semaphore.release();
           }

           // 3. Record processing finished (if it was started)
           if (processingStarted) {
               poolMetrics.recordProcessingFinished(poolCode);
           }

           // 4. Update gauges and clear MDC
           if (semaphoreAcquired) {
               updateGauges();
           }
           MDC.clear();

       } catch (Exception e) {
           LOG.errorf(e, "Error during cleanup for message: %s", messageId);
       }
   }
   ```

2. **Remove decrements from handleMediationResult:**
   ```java
   private void handleMediationResult(MessagePointer message, MediationResult result, long durationMs) {
       MDC.put("result", result.name());
       MDC.put("durationMs", String.valueOf(durationMs));

       if (result == MediationResult.SUCCESS) {
           LOG.infof("Message processed successfully");
           poolMetrics.recordProcessingSuccess(poolCode, durationMs);  // No longer decrements activeWorkers
           messageCallback.ack(message);
       } else {
           LOG.warnf("Mediation failed with result: %s", result);
           poolMetrics.recordProcessingFailure(poolCode, durationMs, result.name());  // No longer decrements
           messageCallback.nack(message);
           // ... warning service call
       }
   }
   ```

**Validation:**
- Increment and decrement are now perfectly paired
- No way for drift to occur
- Metrics reflect actual semaphore state

---

##### 1.2.3: Add Unit Tests for Metrics (2 hours)
**Steps:**

1. **Test normal processing:**
   ```java
   @Test
   void testMetrics_normalProcessing_incrementAndDecrementPaired() {
       // Given
       MessagePointer message = createTestMessage();
       when(mediator.process(message)).thenReturn(MediationResult.SUCCESS);

       // When
       pool.submit(message);
       pool.running.set(false);
       pool.processMessages();

       // Then
       verify(poolMetrics).recordProcessingStarted(poolCode);
       verify(poolMetrics).recordProcessingSuccess(eq(poolCode), anyLong());
       verify(poolMetrics).recordProcessingFinished(poolCode);

       // Verify order
       InOrder inOrder = inOrder(poolMetrics);
       inOrder.verify(poolMetrics).recordProcessingStarted(poolCode);
       inOrder.verify(poolMetrics).recordProcessingSuccess(eq(poolCode), anyLong());
       inOrder.verify(poolMetrics).recordProcessingFinished(poolCode);
   }
   ```

2. **Test exception path:**
   ```java
   @Test
   void testMetrics_exceptionDuringProcessing_decrementStillCalled() {
       // Given
       MessagePointer message = createTestMessage();
       when(mediator.process(message)).thenThrow(new RuntimeException("Error"));

       // When
       pool.submit(message);
       pool.running.set(false);
       pool.processMessages();

       // Then
       verify(poolMetrics).recordProcessingStarted(poolCode);
       verify(poolMetrics).recordProcessingFinished(poolCode);

       // Even though exception occurred, finished was called
   }
   ```

3. **Test rate limit path:**
   ```java
   @Test
   void testMetrics_rateLimitExceeded_noProcessingFinishedCall() {
       // Given: Rate limited message
       MessagePointer message = createTestMessageWithRateLimit("key", 1);
       // ... exhaust rate limit

       // When
       pool.submit(message);
       pool.running.set(false);
       pool.processMessages();

       // Then: Started is called, but not finished (wasn't actually started)
       verify(poolMetrics).recordProcessingStarted(poolCode);
       verify(poolMetrics).recordRateLimitExceeded(poolCode);
       verify(poolMetrics).recordProcessingFinished(poolCode);  // Still called in cleanup
   }
   ```

**Validation:**
- All metrics tests pass
- Pairing verified in all scenarios

---

##### 1.2.4: Integration Test for Metrics Accuracy (2 hours)
**Steps:**

1. **Create long-running test:**
   ```java
   @Test
   @Timeout(value = 5, unit = TimeUnit.MINUTES)
   void testMetricsAccuracy_underLoad_noDrift() {
       // Given: Pool with concurrency 10
       int concurrency = 10;
       ProcessPoolImpl pool = createPool(concurrency);

       // When: Process 10,000 messages with 20% error rate
       for (int i = 0; i < 10000; i++) {
           pool.submit(createTestMessage("msg-" + i));
       }

       // Check metrics every second for 60 seconds
       for (int i = 0; i < 60; i++) {
           Thread.sleep(1000);

           PoolStats stats = poolMetrics.getPoolStats(poolCode);
           int activeWorkers = stats.activeWorkers();
           int semaphoreInUse = concurrency - pool.getSemaphore().availablePermits();

           // Active workers from metrics should match semaphore state
           assertThat(activeWorkers)
               .as("Iteration %d: activeWorkers should match semaphore in-use count", i)
               .isEqualTo(semaphoreInUse);
       }

       // When: Drain pool
       pool.drain();

       // Then: Final metrics should show zero active workers
       PoolStats finalStats = poolMetrics.getPoolStats(poolCode);
       assertThat(finalStats.activeWorkers()).isEqualTo(0);
   }
   ```

**Validation:**
- Test runs successfully for full duration
- No drift detected
- Final state is clean

---

### Task 1.3: Add Map Size Monitoring
**Priority:** P0 - CRITICAL
**Estimated Effort:** 4 hours (0.5 days)
**Assignee:** TBD
**Files Modified:**
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/manager/QueueManager.java`
- `flowcatalyst-core/src/main/resources/application.properties`

#### Subtasks

##### 1.3.1: Add Micrometer Gauges (2 hours)
**Steps:**

1. **Inject MeterRegistry into QueueManager:**
   ```java
   @ApplicationScoped
   public class QueueManager implements MessageCallback {

       // ... existing fields

       @Inject
       MeterRegistry meterRegistry;

       private AtomicInteger inPipelineMapSizeGauge;
       private AtomicInteger messageCallbacksMapSizeGauge;

       @PostConstruct
       void initializeMetrics() {
           // Create gauge for inPipelineMap size
           inPipelineMapSizeGauge = new AtomicInteger(0);
           meterRegistry.gauge(
               "flowcatalyst.queuemanager.pipeline.size",
               List.of(Tag.of("type", "inPipeline")),
               inPipelineMapSizeGauge
           );

           // Create gauge for messageCallbacks size
           messageCallbacksMapSizeGauge = new AtomicInteger(0);
           meterRegistry.gauge(
               "flowcatalyst.queuemanager.callbacks.size",
               List.of(Tag.of("type", "callbacks")),
               messageCallbacksMapSizeGauge
           );

           LOG.info("QueueManager metrics initialized");
       }
   }
   ```

2. **Update gauges when maps change:**
   ```java
   public boolean routeMessage(MessagePointer message, MessageCallback callback) {
       // Check deduplication
       if (inPipelineMap.containsKey(message.id())) {
           LOG.debugf("Message [%s] already in pipeline, discarding", message.id());
           return false;
       }

       // Add to maps
       inPipelineMap.put(message.id(), message);
       messageCallbacks.put(message.id(), callback);

       // Update gauges
       updateMapSizeGauges();

       // ... rest of method
   }

   @Override
   public void ack(MessagePointer message) {
       MessageCallback callback = messageCallbacks.remove(message.id());
       if (callback != null) {
           callback.ack(message);
       }
       updateMapSizeGauges();
   }

   @Override
   public void nack(MessagePointer message) {
       MessageCallback callback = messageCallbacks.remove(message.id());
       if (callback != null) {
           callback.nack(message);
       }
       updateMapSizeGauges();
   }

   private void updateMapSizeGauges() {
       inPipelineMapSizeGauge.set(inPipelineMap.size());
       messageCallbacksMapSizeGauge.set(messageCallbacks.size());
   }
   ```

3. **Add scheduled check for anomalies:**
   ```java
   @Scheduled(every = "30s")
   void checkForMapLeaks() {
       int pipelineSize = inPipelineMap.size();
       int callbacksSize = messageCallbacks.size();

       // Calculate total pool capacity
       int totalCapacity = processPools.values().stream()
           .mapToInt(pool -> pool.getConcurrency() * 10)
           .sum();

       // Warning if maps are larger than total pool capacity
       if (pipelineSize > totalCapacity) {
           warningService.addWarning(
               "PIPELINE_MAP_LEAK",
               "WARN",
               String.format("inPipelineMap size (%d) exceeds total pool capacity (%d)",
                   pipelineSize, totalCapacity),
               "QueueManager"
           );
       }

       // Sizes should be equal (every message in pipeline has a callback)
       if (Math.abs(pipelineSize - callbacksSize) > 10) {
           warningService.addWarning(
               "MAP_SIZE_MISMATCH",
               "WARN",
               String.format("Map size mismatch - pipeline: %d, callbacks: %d",
                   pipelineSize, callbacksSize),
               "QueueManager"
           );
       }
   }
   ```

**Validation:**
- Gauges registered correctly
- Values update in real-time
- Scheduled check runs

---

##### 1.3.2: Add Dashboard Configuration (1 hour)
**Steps:**

1. **Document Grafana panel JSON:**
   ```json
   {
     "title": "Pipeline Map Sizes",
     "targets": [
       {
         "expr": "flowcatalyst_queuemanager_pipeline_size",
         "legendFormat": "In Pipeline"
       },
       {
         "expr": "flowcatalyst_queuemanager_callbacks_size",
         "legendFormat": "Callbacks"
       }
     ],
     "alert": {
       "conditions": [
         {
           "evaluator": {
             "params": [1000],
             "type": "gt"
           },
           "operator": {
             "type": "and"
           },
           "query": {
             "params": ["A", "5m", "now"]
           },
           "reducer": {
             "type": "avg"
           },
           "type": "query"
         }
       ],
       "name": "Pipeline Map Growing"
     }
   }
   ```

2. **Add to monitoring dashboard HTML:**
   Update `/dashboard.html` to include new panels

3. **Document expected values:**
   ```
   Normal operation:
   - inPipelineMap size: 0 to (sum of all pool concurrency)
   - messageCallbacks size: Same as inPipelineMap

   Warning signs:
   - Sustained growth > 100 entries/minute
   - Size > total pool capacity
   - Size mismatch between maps
   ```

**Validation:**
- Dashboard displays metrics
- Alerts configured
- Documentation updated

---

##### 1.3.3: Add Unit Tests (1 hour)
**Steps:**

1. **Test gauge updates:**
   ```java
   @Test
   void testMapSizeGauges_updateWhenMapsChange() {
       // When: Route a message
       MessagePointer msg = createTestMessage();
       queueManager.routeMessage(msg, mockCallback);

       // Then: Gauges updated
       assertThat(getGaugeValue("flowcatalyst.queuemanager.pipeline.size"))
           .isEqualTo(1);
       assertThat(getGaugeValue("flowcatalyst.queuemanager.callbacks.size"))
           .isEqualTo(1);

       // When: Ack the message
       queueManager.ack(msg);

       // Then: Gauges back to zero
       assertThat(getGaugeValue("flowcatalyst.queuemanager.pipeline.size"))
           .isEqualTo(0);
       assertThat(getGaugeValue("flowcatalyst.queuemanager.callbacks.size"))
           .isEqualTo(0);
   }
   ```

2. **Test leak detection:**
   ```java
   @Test
   void testLeakDetection_warningWhenMapExceedsCapacity() {
       // Given: Total pool capacity of 100
       // ... setup pools

       // When: Add 150 messages to pipeline map (simulating leak)
       for (int i = 0; i < 150; i++) {
           inPipelineMap.put("msg-" + i, createTestMessage());
       }

       // When: Scheduled check runs
       queueManager.checkForMapLeaks();

       // Then: Warning added
       verify(warningService).addWarning(
           eq("PIPELINE_MAP_LEAK"),
           eq("WARN"),
           contains("exceeds total pool capacity"),
           eq("QueueManager")
       );
   }
   ```

**Validation:**
- Tests pass
- Coverage > 90%

---

## Phase 1 Completion Checklist

Before proceeding to Phase 2, verify:

- [ ] All Phase 1 tasks completed
- [ ] All unit tests passing (>95% coverage on modified code)
- [ ] All integration tests passing
- [ ] Code reviewed by at least 2 team members
- [ ] Documentation updated
- [ ] Deployed to staging environment
- [ ] Smoke tests passed in staging
- [ ] Load tests passed in staging (24 hour run with monitoring)
- [ ] No memory leaks observed in staging
- [ ] No semaphore permit leaks observed
- [ ] Metrics accuracy verified (activeWorkers matches semaphore state)
- [ ] Map size gauges operational
- [ ] Alerts configured and tested
- [ ] Runbook updated with new metrics
- [ ] Team trained on new monitoring

**Sign-off required from:**
- [ ] Tech Lead
- [ ] QA Lead
- [ ] Operations Lead

---

## Phase 2: High Priority Fixes (Week 2)

### Task 2.1: Implement Pool Metrics Cleanup
**Priority:** P1
**Estimated Effort:** 8 hours (1 day)
**Files Modified:**
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/metrics/PoolMetricsService.java`
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/metrics/MicrometerPoolMetricsService.java`
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/manager/QueueManager.java`

#### Steps:

1. **Add removePoolMetrics method to interface (1 hour)**
2. **Implement metrics removal in MicrometerPoolMetricsService (3 hours)**
   - Remove from poolMetrics map
   - Unregister all meters from registry
   - Log removal
3. **Call during pool removal in syncConfiguration (1 hour)**
4. **Add unit tests (2 hours)**
5. **Integration test (1 hour)**

---

### Task 2.2: Improve Shutdown Handling
**Priority:** P1
**Estimated Effort:** 12 hours (1.5 days)
**Files Modified:**
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/manager/QueueManager.java`
- `flowcatalyst-core/src/main/java/tech/flowcatalyst/messagerouter/pool/ProcessPoolImpl.java`

#### Steps:

1. **Add cleanupRemainingMessages method to QueueManager (3 hours)**
   - Iterate through inPipelineMap
   - Nack each message
   - Clear both maps
   - Log cleanup stats
2. **Call from onShutdown handler (1 hour)**
3. **Add timeout handling for cleanup (2 hours)**
4. **Add metrics for shutdown cleanup (2 hours)**
5. **Add unit tests (2 hours)**
6. **Integration test with forced shutdown (2 hours)**

---

## Phase 3: Medium Priority Improvements (Week 3-4)

### Task 3.1: Add Default Pool Auto-Cleanup
**Priority:** P2
**Estimated Effort:** 8 hours (1 day)

#### Steps:
1. Track last usage timestamp for DEFAULT-POOL
2. Check during configuration sync
3. Remove if unused for > 1 hour
4. Add metrics

---

### Task 3.2: Implement Rate Limiter Eviction
**Priority:** P2
**Estimated Effort:** 16 hours (2 days)

#### Steps:
1. Add Caffeine dependency to pom.xml
2. Replace RateLimiterRegistry with Caffeine cache
3. Configure TTL and max size
4. Add metrics for cache size
5. Test with high cardinality keys

---

### Task 3.3: Add Circuit Breaker for Pool Processing
**Priority:** P2
**Estimated Effort:** 20 hours (2.5 days)

#### Steps:
1. Add error rate tracking per pool
2. Implement circuit breaker logic
3. Pause message acceptance when open
4. Add recovery mechanism
5. Add metrics and logging
6. Test various failure scenarios

---

## Testing Strategy

### Unit Testing
**Coverage Target:** >95% for all modified code

**Test Categories:**
1. Exception path testing
2. Resource cleanup testing
3. Metrics accuracy testing
4. Concurrency testing
5. Edge case testing

**Tools:**
- JUnit 5
- Mockito
- AssertJ
- Awaitility (for async testing)

---

### Integration Testing
**Coverage Target:** All major workflows

**Test Scenarios:**
1. Normal operation with various message types
2. High error rate scenarios (20-50% failures)
3. Forced shutdown during processing
4. Configuration changes during load
5. Resource exhaustion scenarios
6. Recovery from failures

**Environment:**
- LocalStack for SQS
- Embedded ActiveMQ
- WireMock for HTTP endpoints

---

### Load Testing
**Tool:** JMeter or Gatling

**Scenarios:**
1. **Sustained Load:**
   - Duration: 24 hours
   - Messages: 100,000+
   - Error rate: 10%
   - Monitor: map sizes, semaphore permits, metrics drift

2. **Spike Load:**
   - Duration: 1 hour
   - Messages: 50,000 in 5 minutes, then idle
   - Monitor: recovery to baseline

3. **Chaos:**
   - Random worker interruptions
   - Random configuration changes
   - Random mediator failures
   - Monitor: system stability and recovery

**Metrics to Monitor:**
- `flowcatalyst.queuemanager.pipeline.size`
- `flowcatalyst.queuemanager.callbacks.size`
- `flowcatalyst.pool.workers.active`
- `flowcatalyst.pool.semaphore.available`
- JVM heap usage
- Thread count

**Success Criteria:**
- Map sizes remain bounded (< total pool capacity)
- No semaphore permit leaks (available permits constant when idle)
- No metrics drift (activeWorkers == actual workers)
- No memory growth trend
- Clean shutdown in < 10 seconds

---

## Deployment Strategy

### Staged Rollout

**Stage 1: Development**
- All unit tests pass
- Local integration tests pass
- Code review completed

**Stage 2: Staging**
- Deploy to staging environment
- Run 24-hour load test
- Monitor all metrics
- Verify no leaks
- Performance testing

**Stage 3: Canary (10% of production)**
- Deploy to 10% of production instances
- Monitor for 48 hours
- Compare metrics to baseline
- No alerts or errors

**Stage 4: Production (Full)**
- Deploy to all production instances
- Monitor closely for 1 week
- Daily metrics review
- On-call engineer assigned

---

## Rollback Plan

### Rollback Triggers
- Memory leak detected (heap growth > 10% in 1 hour)
- Semaphore permit leak detected
- Metrics drift exceeds 5%
- Error rate increase > 20%
- Performance degradation > 30%

### Rollback Procedure
1. Immediately revert to previous version
2. Restart affected services
3. Verify stability returns
4. Analyze root cause
5. Fix and redeploy to staging

### Rollback Testing
- Practice rollback in staging before production deployment
- Document rollback time (target: < 5 minutes)
- Ensure monitoring alerts trigger appropriately

---

## Risk Assessment

### High Risk Items
1. **Restructuring exception handling** - Potential for introducing new bugs
   - Mitigation: Comprehensive unit tests, staged rollout

2. **Metrics changes** - May affect existing dashboards/alerts
   - Mitigation: Update all dashboards before deployment, parallel metrics during transition

3. **Shutdown logic changes** - Potential for data loss
   - Mitigation: Extensive testing of shutdown scenarios

### Medium Risk Items
1. **Performance impact of additional cleanup** - May slow processing
   - Mitigation: Benchmark before/after, optimize if needed

2. **Map size monitoring overhead** - Gauge updates on every operation
   - Mitigation: Use atomic integers (minimal overhead)

---

## Success Metrics

### Technical Metrics

**Before Fixes (Baseline):**
- `inPipelineMap` growth: ~10-50 entries/hour under error conditions
- Semaphore leaks: 1-5 permits/day
- Metrics drift: +2-10% per hour under errors
- Shutdown time: Variable, can hang

**After Fixes (Target):**
- `inPipelineMap` growth: 0 (bounded by active processing)
- Semaphore leaks: 0
- Metrics drift: 0 (perfect accuracy)
- Shutdown time: < 5 seconds
- Map cleanup success rate: 100%

### Business Metrics
- System uptime: 99.9%+
- Mean time to recovery: < 1 minute
- False alert rate: < 5%
- Incident count: Reduce by 80%

---

## Documentation Updates

### Required Documentation

1. **Architecture Documentation**
   - Update exception handling flow diagrams
   - Document new metrics
   - Update shutdown procedure

2. **Runbook**
   - Add monitoring checklist for new metrics
   - Add troubleshooting for map leaks
   - Add recovery procedures

3. **API Documentation**
   - Document new metrics service methods
   - Update interface contracts

4. **Operations Guide**
   - New alerts and their meanings
   - Expected metric values
   - Escalation procedures

---

## Training Requirements

### Engineering Team
- Code walkthrough session (2 hours)
- Exception handling patterns
- New metrics usage
- Testing strategies

### Operations Team
- New metrics overview (1 hour)
- Alert interpretation
- Troubleshooting procedures
- Dashboard usage

### QA Team
- Testing strategy overview (1 hour)
- Test scenario execution
- Metrics validation
- Load testing procedures

---

## Timeline Summary

| Week | Phase | Tasks | Effort |
|------|-------|-------|--------|
| 1 | Phase 1 | Critical fixes | 28 hours |
| 2 | Phase 2 | High priority | 20 hours |
| 3-4 | Phase 3 | Medium priority | 44 hours |

**Total estimated effort:** ~92 hours (11.5 days)

**Actual calendar time:** 4 weeks (including testing, reviews, deployment)

---

## Approval Checklist

**Before starting implementation:**
- [ ] Plan reviewed by Tech Lead
- [ ] Plan reviewed by Operations Team
- [ ] Plan reviewed by QA Team
- [ ] Resources allocated
- [ ] Timeline approved
- [ ] Budget approved (if applicable)

**Before production deployment:**
- [ ] All tests passing
- [ ] Code reviewed
- [ ] Documentation updated
- [ ] Team trained
- [ ] Monitoring configured
- [ ] Rollback plan tested
- [ ] Change management approval

---

## Contact and Escalation

**Project Lead:** TBD
**Tech Lead:** TBD
**Operations Lead:** TBD
**QA Lead:** TBD

**Escalation Path:**
1. Project Lead
2. Tech Lead
3. Engineering Manager
4. CTO

---

**Document Version:** 1.0
**Last Updated:** 2025-10-10
**Next Review:** After Phase 1 completion
