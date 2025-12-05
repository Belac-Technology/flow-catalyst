# Integration & End-to-End Test Implementation Plan

## Executive Summary

This plan addresses the gaps in our test coverage by adding comprehensive integration and end-to-end tests. The goal is to validate the complete message routing system with real infrastructure components.

**Current State**: 124 unit tests passing, 8 integration test files with good coverage of individual components
**Target State**: Complete E2E coverage, batch+group FIFO integration tests, resilience testing, and performance validation

## Test Infrastructure

All integration and E2E tests use **local containerized infrastructure** - no real cloud services required:

- **LocalStack** (already configured): Provides local AWS SQS (including FIFO queues) via TestContainers
  - Supports all SQS features: FIFO queues, messageGroupId, deduplication, visibility timeout
  - No AWS credentials needed
  - Fast startup and teardown
  - Already in use: `LocalStackTestResource.java`

- **TestContainers ActiveMQ** (already configured): Provides local ActiveMQ Classic broker
  - Full JMS support including individual acknowledgment
  - Already in use: `ActiveMQTestResource.java`

- **WireMock** (to be added): Provides mock HTTP endpoints for testing mediators
  - Simulates downstream services
  - Configurable responses, delays, errors
  - Request verification

**Benefits**:
- ✅ No cloud costs
- ✅ No network dependencies
- ✅ Fast and reliable CI/CD execution
- ✅ Reproducible tests
- ✅ Can run offline

---

## Phase 1: End-to-End Test Infrastructure (Priority: HIGH)

### 1.1 Create WireMock Test Resource

**File**: `src/test/java/tech/flowcatalyst/messagerouter/integration/WireMockTestResource.java`

**Purpose**: Provide mock HTTP endpoints for E2E tests without requiring real downstream services.

**Implementation**:
```java
@QuarkusTestResourceLifecycleManager
public class WireMockTestResource implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(options()
            .port(8089)
            .dynamicPort()
        );
        wireMockServer.start();

        // Configure default stubs
        wireMockServer.stubFor(post("/webhook/success")
            .willReturn(ok().withBody("OK")));

        wireMockServer.stubFor(post("/webhook/slow")
            .willReturn(ok().withFixedDelay(5000)));

        wireMockServer.stubFor(post("/webhook/error")
            .willReturn(serverError().withStatus(500)));

        return Map.of(
            "test.webhook.baseurl", wireMockServer.baseUrl()
        );
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    public WireMockServer getServer() {
        return wireMockServer;
    }
}
```

**Dependencies Required**:
- Add to `build.gradle.kts`:
```kotlin
testImplementation("org.wiremock:wiremock:3.3.1")
```

**Acceptance Criteria**:
- ✅ WireMock server starts on dynamic port
- ✅ Default stubs configured for success, slow, and error endpoints
- ✅ Server URL injected into test properties
- ✅ Server stops cleanly after tests

---

## Phase 2: Complete End-to-End Tests (Priority: HIGH)

### 2.1 Create CompleteEndToEndTest

**File**: `src/test/java/tech/flowcatalyst/messagerouter/integration/CompleteEndToEndTest.java`

**Purpose**: Validate the complete message flow from queue to HTTP endpoint.

**Test Scenarios**:

#### Test 2.1.1: Basic Message Flow - Success Path
```java
@Test
void shouldProcessMessageFromSqsToHttpEndpoint() {
    // Given: Configure pool and queue
    // - Create SQS queue
    // - Create processing pool with WireMock target
    // - Start queue consumer

    // When: Send message to SQS
    String messageBody = createMessageJson("msg-e2e-1", poolCode,
        wireMockServer.baseUrl() + "/webhook/success");
    sqsClient.sendMessage(queueUrl, messageBody);

    // Then: Verify complete flow
    // 1. Message received by consumer
    // 2. Routed to correct pool
    // 3. HTTP call made to WireMock endpoint
    // 4. Message ACKed and deleted from queue
    // 5. Metrics updated (received, processed, success)

    await().atMost(10, SECONDS).untilAsserted(() -> {
        // Verify HTTP call was made
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook/success")));

        // Verify message deleted from queue
        ReceiveMessageResponse response = sqsClient.receiveMessage(queueUrl);
        assertTrue(response.messages().isEmpty());

        // Verify metrics
        assertEquals(1, metricsService.getMessagesReceived(queueUrl));
        assertEquals(1, metricsService.getMessagesProcessed(poolCode, true));
    });
}
```

**Acceptance Criteria**:
- ✅ Message flows from SQS → Consumer → QueueManager → ProcessPool → HttpMediator → WireMock
- ✅ Message is ACKed and deleted after success
- ✅ All metrics are updated correctly
- ✅ Test completes in < 15 seconds

#### Test 2.1.2: Message Processing with Failure and Retry
```java
@Test
void shouldRetryMessageOnFailureViaVisibilityTimeout() {
    // Given: Configure endpoint to fail first time, succeed second time
    wireMockServer.stubFor(post("/webhook/flaky")
        .inScenario("retry")
        .whenScenarioStateIs(STARTED)
        .willReturn(serverError())
        .willSetStateTo("retry-ready"));

    wireMockServer.stubFor(post("/webhook/flaky")
        .inScenario("retry")
        .whenScenarioStateIs("retry-ready")
        .willReturn(ok()));

    // When: Send message (visibility timeout = 2 seconds)
    sqsClient.sendMessage(queueUrl, messageBody);

    // Then: Verify retry behavior
    await().atMost(10, SECONDS).untilAsserted(() -> {
        // First attempt fails (NACKed)
        // Message becomes visible again after 2 seconds
        // Second attempt succeeds (ACKed)
        wireMockServer.verify(2, postRequestedFor(urlEqualTo("/webhook/flaky")));

        // Message eventually deleted
        assertTrue(sqsClient.receiveMessage(queueUrl).messages().isEmpty());
    });
}
```

**Acceptance Criteria**:
- ✅ Failed message is NACKed (not deleted)
- ✅ Message becomes visible again after visibility timeout
- ✅ Message is retried automatically
- ✅ Success on retry deletes message

#### Test 2.1.3: Multiple Queues to Multiple Pools
```java
@Test
void shouldRouteFromMultipleQueuesToMultiplePools() {
    // Given: Two queues, two pools
    String highPriorityQueue = createQueue("high-priority");
    String lowPriorityQueue = createQueue("low-priority");

    String highPool = "POOL-HIGH";
    String lowPool = "POOL-LOW";

    // When: Send messages to both queues
    sqsClient.sendMessage(highPriorityQueue,
        createMessageJson("high-1", highPool, "/webhook/high"));
    sqsClient.sendMessage(lowPriorityQueue,
        createMessageJson("low-1", lowPool, "/webhook/low"));

    // Then: Verify correct routing
    await().atMost(15, SECONDS).untilAsserted(() -> {
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook/high")));
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook/low")));

        assertEquals(1, metricsService.getMessagesProcessed(highPool, true));
        assertEquals(1, metricsService.getMessagesProcessed(lowPool, true));
    });
}
```

**Acceptance Criteria**:
- ✅ Messages route to correct pools based on poolCode
- ✅ Multiple consumers work concurrently
- ✅ No cross-contamination between pools

#### Test 2.1.4: Metrics Throughout Lifecycle
```java
@Test
void shouldUpdateMetricsThroughoutMessageLifecycle() {
    // Given: Fresh metrics state
    String messageId = "msg-metrics-1";

    // When: Send and process message
    sqsClient.sendMessage(queueUrl, createMessageJson(messageId, poolCode, "/webhook/success"));

    // Then: Verify metrics at each stage
    await().atMost(5, SECONDS).untilAsserted(() -> {
        // Stage 1: Message received
        assertTrue(metricsService.getMessagesReceived(queueUrl) >= 1);
    });

    await().atMost(10, SECONDS).untilAsserted(() -> {
        // Stage 2: Message submitted to pool
        assertTrue(poolMetrics.getMessagesSubmitted(poolCode) >= 1);

        // Stage 3: Message processed successfully
        assertTrue(metricsService.getMessagesProcessed(poolCode, true) >= 1);

        // Stage 4: Pool activity timestamp updated
        assertNotNull(poolMetrics.getLastActivityTimestamp(poolCode));
    });
}
```

**Acceptance Criteria**:
- ✅ Metrics updated at each stage: receive → submit → process → complete
- ✅ Timestamps accurately reflect processing time
- ✅ Success/failure counts are accurate

---

## Phase 3: Batch+Group FIFO Integration Tests (Priority: HIGH)

### 3.1 Create BatchGroupFifoIntegrationTest

**File**: `src/test/java/tech/flowcatalyst/messagerouter/integration/BatchGroupFifoIntegrationTest.java`

**Purpose**: Validate batch+group FIFO enforcement with SQS FIFO queues using LocalStack.

**Important**: This test uses LocalStack SQS FIFO queues (`.fifo` suffix) with messageGroupId to ensure ordering. LocalStack fully supports FIFO queues with all features including messageGroupId, deduplication, and ordering guarantees.

#### Test 3.1.1: FIFO Ordering in LocalStack SQS FIFO Queue
```java
@QuarkusTest
@QuarkusTestResource(LocalStackTestResource.class)
@Tag("integration")
class BatchGroupFifoIntegrationTest {

    @Inject
    SqsClient sqsClient;  // Automatically configured to use LocalStack

    @Test
    void shouldMaintainFifoOrderingInLocalStackSqsFifoQueue() {
        // Given: Create SQS FIFO queue in LocalStack
        String queueName = "test-fifo-" + UUID.randomUUID() + ".fifo";
        String queueUrl = sqsClient.createQueue(CreateQueueRequest.builder()
        .queueName(queueName)
        .attributes(Map.of(
            QueueAttributeName.FIFO_QUEUE, "true",
            QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"
        ))
        .build()).queueUrl();

    // Configure processing to record order
    List<String> processedOrder = new CopyOnWriteArrayList<>();

    // When: Send 10 messages with same messageGroupId to FIFO queue
    String messageGroupId = "order-12345";
    for (int i = 0; i < 10; i++) {
        sqsClient.sendMessage(SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(createMessageJson("msg-" + i, poolCode, "/webhook/success"))
            .messageGroupId(messageGroupId)
            .messageDeduplicationId(UUID.randomUUID().toString())
            .build());
    }

    // Configure WireMock to track order
    wireMockServer.stubFor(post("/webhook/success")
        .willReturn(ok())
        .willDoPostServeAction(new Action() {
            void doAction(ServeEvent event) {
                String body = event.getRequest().getBodyAsString();
                processedOrder.add(extractMessageId(body));
            }
        }));

    // Then: Verify strict FIFO order
    await().atMost(30, SECONDS).until(() -> processedOrder.size() == 10);

    for (int i = 0; i < 10; i++) {
        assertEquals("msg-" + i, processedOrder.get(i),
            "Message at index " + i + " should be msg-" + i);
    }
}
```

**Acceptance Criteria**:
- ✅ Uses LocalStack SQS FIFO queue
- ✅ Messages with same messageGroupId process in strict FIFO order
- ✅ Order maintained even with concurrent consumers
- ✅ Test runs without requiring AWS credentials or real AWS infrastructure

#### Test 3.1.2: Cascading Nacks on Batch+Group Failure
```java
@Test
void shouldCascadeNacksWhenBatchGroupFailsMidway() {
    // Given: FIFO queue with batch of 5 messages
    String queueUrl = createFifoQueue();
    String messageGroupId = "order-67890";
    String batchId = UUID.randomUUID().toString();

    // Configure endpoint to fail message 2
    wireMockServer.stubFor(post("/webhook/batch")
        .withRequestBody(containing("\"id\":\"msg-2\""))
        .willReturn(serverError()));

    wireMockServer.stubFor(post("/webhook/batch")
        .willReturn(ok()));

    // When: Send batch of 5 messages
    for (int i = 0; i < 5; i++) {
        sqsClient.sendMessage(SendMessageRequest.builder()
            .queueUrl(queueUrl)
            .messageBody(createMessageJson("msg-" + i, poolCode, "/webhook/batch"))
            .messageGroupId(messageGroupId)
            .messageDeduplicationId(batchId + "-" + i)
            .build());
    }

    // Then: Verify cascading nack behavior
    await().atMost(30, SECONDS).untilAsserted(() -> {
        // msg-0 and msg-1 should succeed
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook/batch"))
            .withRequestBody(containing("\"id\":\"msg-0\"")));
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook/batch"))
            .withRequestBody(containing("\"id\":\"msg-1\"")));

        // msg-2 should fail
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook/batch"))
            .withRequestBody(containing("\"id\":\"msg-2\"")));

        // msg-3 and msg-4 should be NACKed without processing
        // (pre-flight check catches failed batch+group)
        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/webhook/batch"))
            .withRequestBody(containing("\"id\":\"msg-3\"")));
        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/webhook/batch"))
            .withRequestBody(containing("\"id\":\"msg-4\"")));

        // Verify messages 3 and 4 are back in queue (nacked)
        ReceiveMessageResponse response = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .build());

        // Should see msg-2, msg-3, msg-4 available for retry
        assertTrue(response.messages().size() >= 3);
    });
}
```

**Acceptance Criteria**:
- ✅ Messages before failure process successfully
- ✅ Failed message is NACKed
- ✅ Subsequent messages in same batch+group are NACKed immediately
- ✅ NACKed messages become visible for retry
- ✅ Batch+group tracking cleaned up after all messages processed

#### Test 3.1.3: Multiple Message Groups Process Concurrently
```java
@Test
void shouldProcessDifferentMessageGroupsConcurrentlyInRealQueue() {
    // Given: FIFO queue with 3 different message groups
    String queueUrl = createFifoQueue();

    // Configure slow endpoint to test concurrency
    wireMockServer.stubFor(post("/webhook/concurrent")
        .willReturn(ok().withFixedDelay(2000)));

    Set<String> concurrentGroups = ConcurrentHashMap.newKeySet();
    AtomicInteger activeCount = new AtomicInteger(0);
    AtomicInteger maxConcurrent = new AtomicInteger(0);

    wireMockServer.stubFor(post("/webhook/concurrent")
        .willDoPostServeAction(new Action() {
            void doAction(ServeEvent event) {
                String groupId = extractMessageGroupId(event.getRequest());
                concurrentGroups.add(groupId);
                int active = activeCount.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, active));

                try {
                    Thread.sleep(2000); // Simulate processing
                } finally {
                    activeCount.decrementAndGet();
                }
            }
        }));

    long startTime = System.currentTimeMillis();

    // When: Send 10 messages for each of 3 groups
    for (int g = 1; g <= 3; g++) {
        String groupId = "group-" + g;
        for (int m = 0; m < 10; m++) {
            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(createMessageJson("msg-" + g + "-" + m, poolCode, "/webhook/concurrent"))
                .messageGroupId(groupId)
                .messageDeduplicationId(UUID.randomUUID().toString())
                .build());
        }
    }

    // Then: Verify concurrent processing
    await().atMost(60, SECONDS).until(() ->
        wireMockServer.getAllServeEvents().size() == 30);

    long duration = System.currentTimeMillis() - startTime;

    // Should see multiple groups processed concurrently
    assertTrue(concurrentGroups.size() >= 3, "All 3 groups should process");
    assertTrue(maxConcurrent.get() >= 2, "At least 2 groups should process concurrently");

    // Should complete faster than serial (30 * 2s = 60s)
    // With concurrency should be closer to 20-30s
    assertTrue(duration < 40000,
        "Should complete in < 40s with concurrency, took: " + duration + "ms");
}
```

**Acceptance Criteria**:
- ✅ Different message groups process concurrently
- ✅ Same message group processes sequentially (FIFO)
- ✅ Overall processing time shows parallelism

---

## Phase 4: Resilience & Chaos Tests (Priority: MEDIUM)

### 4.1 Create ResilienceIntegrationTest

**File**: `src/test/java/tech/flowcatalyst/messagerouter/integration/ResilienceIntegrationTest.java`

**Purpose**: Validate system behavior under failure conditions and recovery scenarios.

#### Test 4.1.1: SQS Connection Loss and Recovery (LocalStack)
```java
@QuarkusTest
@QuarkusTestResource(LocalStackTestResource.class)
@Tag("integration")
class ResilienceIntegrationTest {

    @Inject
    SqsClient sqsClient;  // Connected to LocalStack

    // Access to LocalStack container for chaos testing
    private LocalStackContainer localStackContainer;

    @Test
    void shouldRecoverWhenSqsConnectionLost() {
        // Given: System running with active SQS consumer connected to LocalStack
        // When: LocalStack container is paused (simulates network/broker failure)
        localStackContainer.pause();

    // Then: Verify health check fails
    await().atMost(30, SECONDS).untilAsserted(() -> {
        assertFalse(healthService.isHealthy());
        assertTrue(healthService.getStatus().getMessage().contains("SQS broker"));
    });

    // When: LocalStack container is resumed
    localStackContainer.unpause();

    // Then: Verify reconnection and recovery
    await().atMost(60, SECONDS).untilAsserted(() -> {
        assertTrue(healthService.isHealthy());

        // Verify can process messages again
        sqsClient.sendMessage(queueUrl, createMessageJson("recovery-msg", poolCode, "/webhook/success"));
        wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook/success")));
    });
}
```

**Acceptance Criteria**:
- ✅ System detects connection loss
- ✅ Health check reports unhealthy
- ✅ System reconnects automatically when broker available
- ✅ Message processing resumes after recovery

#### Test 4.1.2: HTTP Endpoint Timeout Handling
```java
@Test
void shouldHandleHttpEndpointTimeouts() {
    // Given: Endpoint configured with excessive delay (> timeout)
    wireMockServer.stubFor(post("/webhook/timeout")
        .willReturn(ok().withFixedDelay(35000))); // 35 seconds (timeout is 30s)

    // When: Send message
    sqsClient.sendMessage(queueUrl,
        createMessageJson("timeout-msg", poolCode, "/webhook/timeout"));

    // Then: Verify timeout handling
    await().atMost(45, SECONDS).untilAsserted(() -> {
        // Message should fail with timeout
        verify(warningService).addWarning(
            eq("MEDIATION"),
            eq("ERROR"),
            contains("timeout"),
            any()
        );

        // Message should be NACKed (available for retry)
        ReceiveMessageResponse response = sqsClient.receiveMessage(queueUrl);
        assertFalse(response.messages().isEmpty());

        // Metrics should reflect failure
        assertTrue(metricsService.getMessagesProcessed(poolCode, false) >= 1);
    });
}
```

**Acceptance Criteria**:
- ✅ HTTP timeout is detected
- ✅ Message is NACKed for retry
- ✅ Warning is logged
- ✅ Metrics reflect failure

#### Test 4.1.3: Rate Limit Enforcement Under Load
```java
@Test
void shouldEnforceRateLimitAndBackpressure() {
    // Given: Pool with rate limit of 10 messages/minute
    String rateLimitedPool = "POOL-RATE-LIMITED";
    createPoolWithRateLimit(rateLimitedPool, 10);

    // When: Send 20 messages rapidly
    for (int i = 0; i < 20; i++) {
        sqsClient.sendMessage(queueUrl,
            createMessageJson("rate-msg-" + i, rateLimitedPool, "/webhook/success"));
    }

    long startTime = System.currentTimeMillis();

    // Then: Verify rate limiting
    await().atMost(2, MINUTES).untilAsserted(() -> {
        int processedCount = wireMockServer.getAllServeEvents().size();

        // After ~60 seconds, should have processed ~10 messages
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed >= 60000) {
            assertTrue(processedCount >= 8 && processedCount <= 12,
                "Should process ~10 messages in first minute, got: " + processedCount);
        }

        // Verify excess messages are NACKed and available
        ReceiveMessageResponse response = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .build());
        assertTrue(response.messages().size() >= 5, "Excess messages should be available");
    });
}
```

**Acceptance Criteria**:
- ✅ Rate limit is enforced (10/minute)
- ✅ Excess messages are NACKed
- ✅ System doesn't crash under rate limit pressure
- ✅ Messages eventually process as rate limit allows

#### Test 4.1.4: Pool Queue Saturation
```java
@Test
void shouldHandlePoolQueueSaturationGracefully() {
    // Given: Pool with small queue capacity (10)
    String smallPool = "POOL-SMALL-QUEUE";
    createPoolWithQueueSize(smallPool, 10);

    // Configure slow processing to cause backlog
    wireMockServer.stubFor(post("/webhook/slow")
        .willReturn(ok().withFixedDelay(5000)));

    // When: Send 50 messages rapidly
    for (int i = 0; i < 50; i++) {
        sqsClient.sendMessage(queueUrl,
            createMessageJson("saturate-msg-" + i, smallPool, "/webhook/slow"));
    }

    // Then: Verify graceful degradation
    await().atMost(30, SECONDS).untilAsserted(() -> {
        // Verify warning about queue saturation
        verify(warningService, atLeastOnce()).addWarning(
            eq("POOL_QUEUE_FULL"),
            eq("WARN"),
            contains("queue full"),
            any()
        );

        // Verify messages are NACKed when queue full
        ReceiveMessageResponse response = sqsClient.receiveMessage(
            ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .build());

        // Should see rejected messages back in queue
        assertTrue(response.messages().size() > 0);

        // System should still be healthy (not crashed)
        assertTrue(healthService.isHealthy());
    });
}
```

**Acceptance Criteria**:
- ✅ Queue saturation is detected
- ✅ Warning is logged
- ✅ Messages are NACKed when queue full
- ✅ System remains healthy (no crash)
- ✅ Processing continues as queue drains

---

## Phase 5: Performance & Load Tests (Priority: LOW)

### 5.1 Create LoadPerformanceTest

**File**: `src/test/java/tech/flowcatalyst/messagerouter/integration/LoadPerformanceTest.java`

**Purpose**: Validate system performance under high load and identify bottlenecks.

#### Test 5.1.1: High Throughput - 1000 Messages
```java
@Test
@Tag("performance")
void shouldProcessThousandMessagesEfficiently() {
    // Given: Performance-optimized configuration
    int messageCount = 1000;
    int poolConcurrency = 20;

    createPool(poolCode, poolConcurrency, 500);

    wireMockServer.stubFor(post("/webhook/fast")
        .willReturn(ok().withFixedDelay(50))); // 50ms per message

    long startTime = System.currentTimeMillis();

    // When: Send 1000 messages
    for (int i = 0; i < messageCount; i++) {
        sqsClient.sendMessage(queueUrl,
            createMessageJson("perf-msg-" + i, poolCode, "/webhook/fast"));
    }

    // Then: Verify performance
    await().atMost(2, MINUTES).until(() ->
        wireMockServer.getAllServeEvents().size() == messageCount);

    long duration = System.currentTimeMillis() - startTime;

    // With 20 concurrent workers, 50ms per message
    // Theoretical minimum: 1000 / 20 * 50ms = 2.5 seconds
    // Allow overhead: should complete in < 30 seconds
    assertTrue(duration < 30000,
        "Should process 1000 messages in < 30s, took: " + duration + "ms");

    // Calculate throughput
    double messagesPerSecond = (messageCount * 1000.0) / duration;
    assertTrue(messagesPerSecond > 30,
        "Should achieve > 30 msg/s, got: " + messagesPerSecond);
}
```

**Acceptance Criteria**:
- ✅ 1000 messages process successfully
- ✅ Throughput > 30 messages/second
- ✅ No messages lost or duplicated
- ✅ Memory usage remains stable

#### Test 5.1.2: Concurrent Multi-Pool Load
```java
@Test
@Tag("performance")
void shouldHandleConcurrentLoadAcrossMultiplePools() {
    // Given: 3 pools with different configurations
    String[] pools = {"POOL-A", "POOL-B", "POOL-C"};
    for (String pool : pools) {
        createPool(pool, 10, 200);
    }

    Map<String, AtomicInteger> completionCounts = new ConcurrentHashMap<>();
    for (String pool : pools) {
        completionCounts.put(pool, new AtomicInteger(0));
    }

    // When: Send 300 messages distributed across pools
    for (int i = 0; i < 300; i++) {
        String pool = pools[i % 3];
        sqsClient.sendMessage(queueUrl,
            createMessageJson("multi-msg-" + i, pool, "/webhook/success"));
    }

    // Then: Verify all pools process concurrently
    await().atMost(1, MINUTES).until(() ->
        wireMockServer.getAllServeEvents().size() == 300);

    // Verify balanced distribution
    for (String pool : pools) {
        int count = metricsService.getMessagesProcessed(pool, true);
        assertTrue(count >= 90 && count <= 110,
            "Pool " + pool + " should process ~100 messages, got: " + count);
    }
}
```

**Acceptance Criteria**:
- ✅ Multiple pools process concurrently
- ✅ Load is distributed evenly
- ✅ No pool starvation
- ✅ All messages process successfully

#### Test 5.1.3: Memory Usage Under Sustained Load
```java
@Test
@Tag("performance")
void shouldMaintainStableMemoryUnderSustainedLoad() {
    // Given: Memory monitoring setup
    Runtime runtime = Runtime.getRuntime();
    long initialMemory = runtime.totalMemory() - runtime.freeMemory();

    List<Long> memorySnapshots = new CopyOnWriteArrayList<>();

    // When: Process messages continuously for 2 minutes
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    AtomicBoolean keepSending = new AtomicBoolean(true);

    // Send messages continuously
    CompletableFuture<Void> sender = CompletableFuture.runAsync(() -> {
        int count = 0;
        while (keepSending.get()) {
            sqsClient.sendMessage(queueUrl,
                createMessageJson("mem-msg-" + count++, poolCode, "/webhook/success"));
            Thread.sleep(100); // 10 messages/second
        }
    });

    // Monitor memory every 10 seconds
    scheduler.scheduleAtFixedRate(() -> {
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        memorySnapshots.add(usedMemory);
    }, 0, 10, TimeUnit.SECONDS);

    // Run for 2 minutes
    Thread.sleep(120000);
    keepSending.set(false);
    sender.join();
    scheduler.shutdown();

    // Then: Verify memory stability
    long finalMemory = runtime.totalMemory() - runtime.freeMemory();
    long memoryIncrease = finalMemory - initialMemory;

    // Memory should not increase by more than 100MB
    assertTrue(memoryIncrease < 100_000_000,
        "Memory should remain stable, increased by: " + (memoryIncrease / 1_000_000) + "MB");

    // Verify no continuous upward trend (memory leak)
    for (int i = 1; i < memorySnapshots.size(); i++) {
        long previous = memorySnapshots.get(i - 1);
        long current = memorySnapshots.get(i);
        long increase = current - previous;

        // Each interval should not show significant increase
        assertTrue(increase < 20_000_000,
            "Memory leak detected at interval " + i + ": +" + (increase / 1_000_000) + "MB");
    }
}
```

**Acceptance Criteria**:
- ✅ Memory usage remains stable over 2 minutes
- ✅ No continuous upward trend (no memory leak)
- ✅ Memory increase < 100MB
- ✅ System can run sustainably

---

## Phase 6: Documentation & Test Infrastructure (Priority: LOW)

### 6.1 Update Test Documentation

**File**: `src/test/java/tech/flowcatalyst/messagerouter/test/TESTING_GUIDE.md`

**Updates Required**:
1. Add section on E2E testing best practices
2. Document WireMock usage patterns
3. Document FIFO queue testing patterns
4. Add resilience testing guidelines
5. Add performance testing guidelines

**Example Addition**:
```markdown
## End-to-End Testing

### When to Write E2E Tests

E2E tests should cover:
- Complete message flow from queue to endpoint
- Integration of multiple components
- Real infrastructure (SQS, ActiveMQ)
- Edge cases that span multiple components

### E2E Test Structure

```java
@QuarkusTest
@QuarkusTestResource(LocalStackTestResource.class)
@QuarkusTestResource(WireMockTestResource.class)
@Tag("e2e")
class MyEndToEndTest {

    @Test
    void shouldCompleteFullMessageFlow() {
        // Given: Infrastructure setup
        // When: Send message to queue
        // Then: Verify end-to-end behavior
    }
}
```

### Using WireMock in E2E Tests

WireMock provides mock HTTP endpoints for testing:
```java
// Stub successful endpoint
wireMockServer.stubFor(post("/webhook/success")
    .willReturn(ok()));

// Stub error endpoint
wireMockServer.stubFor(post("/webhook/error")
    .willReturn(serverError()));

// Verify calls
wireMockServer.verify(postRequestedFor(urlEqualTo("/webhook/success")));
```
```

### 6.2 Create Test Utilities for E2E Tests

**File**: `src/test/java/tech/flowcatalyst/messagerouter/integration/E2ETestUtils.java`

**Purpose**: Shared utilities for E2E tests to reduce boilerplate.

```java
public class E2ETestUtils {

    public static String createMessageJson(String id, String poolCode, String target) {
        return String.format("""
            {
                "id": "%s",
                "poolCode": "%s",
                "authToken": "test-token",
                "mediationType": "HTTP",
                "mediationTarget": "%s"
            }
            """, id, poolCode, target);
    }

    public static String createFifoQueue(SqsClient client, String prefix) {
        String queueName = prefix + "-" + UUID.randomUUID() + ".fifo";
        CreateQueueResponse response = client.createQueue(CreateQueueRequest.builder()
            .queueName(queueName)
            .attributes(Map.of(
                QueueAttributeName.FIFO_QUEUE, "true",
                QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false",
                QueueAttributeName.VISIBILITY_TIMEOUT, "30"
            ))
            .build());
        return response.queueUrl();
    }

    public static void waitForQueueEmpty(SqsClient client, String queueUrl, Duration timeout) {
        await().atMost(timeout).untilAsserted(() -> {
            ReceiveMessageResponse response = client.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(1)
                    .build());
            assertTrue(response.messages().isEmpty(), "Queue should be empty");
        });
    }

    public static String extractMessageId(String jsonBody) {
        // Parse JSON and extract "id" field
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(jsonBody);
            return node.get("id").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse message ID", e);
        }
    }
}
```

---

## Implementation Timeline

### Week 1: E2E Infrastructure
- Day 1-2: Create WireMockTestResource
- Day 3-5: Implement CompleteEndToEndTest (all 4 scenarios)

### Week 2: Batch+Group FIFO Integration
- Day 1-3: Create BatchGroupFifoIntegrationTest (all 3 scenarios)
- Day 4-5: Debug and fix any issues found

### Week 3: Resilience Tests
- Day 1-2: Create ResilienceIntegrationTest class
- Day 3-5: Implement all 4 resilience scenarios

### Week 4: Performance Tests & Documentation
- Day 1-3: Create LoadPerformanceTest (all 3 scenarios)
- Day 4-5: Update documentation and create E2ETestUtils

---

## Success Criteria

### Completion Checklist

- [ ] All E2E tests pass consistently (no flakiness)
- [ ] Batch+group FIFO integration tests validate real queue behavior
- [ ] Resilience tests prove system can recover from failures
- [ ] Performance tests establish baseline metrics
- [ ] Documentation updated with E2E testing guidelines
- [ ] CI/CD pipeline configured to run integration tests
- [ ] Test coverage report shows > 80% coverage for critical paths

### Quality Gates

- **E2E Tests**: Must complete in < 5 minutes total
- **Integration Tests**: Must be isolated (no shared state)
- **Performance Tests**: Must be tagged separately (@Tag("performance"))
- **Flakiness**: < 1% flake rate over 100 runs
- **Documentation**: All test files have comprehensive Javadoc

---

## Risk Mitigation

### Potential Issues

1. **Flaky Tests Due to Timing**
   - Mitigation: Use Awaitility with generous timeouts
   - Mitigation: Add retry logic for container startup

2. **TestContainers Resource Usage**
   - Mitigation: Reuse containers where possible
   - Mitigation: Run integration tests in parallel when safe

3. **WireMock Port Conflicts**
   - Mitigation: Use dynamic ports
   - Mitigation: Ensure proper cleanup in @AfterEach

4. **Long Test Execution Time**
   - Mitigation: Tag performance tests separately
   - Mitigation: Run E2E tests only on main branch CI

---

## Appendix: Dependencies Required

Add to `build.gradle.kts`:
```kotlin
dependencies {
    // WireMock for HTTP mocking
    testImplementation("org.wiremock:wiremock:3.3.1")

    // Already have TestContainers
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    testImplementation("org.testcontainers:localstack:1.19.3")

    // Already have Awaitility
    testImplementation("org.awaitility:awaitility:4.2.0")
}
```

---

**END OF PLAN**
