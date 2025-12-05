# Message Group FIFO Ordering - Test Plan

## Overview

This test plan covers verification of the per-message-group FIFO ordering implementation. The tests verify that we correctly solved the original problem: **workers blocking while holding messages they can't process**.

## Problems Addressed (What We're Testing)

### Original Problem
**Scenario:** Single queue with per-group locks
```
Queue: [msg1-group-A, msg2-group-A, msg3-group-A, msg1-group-B]
Workers: 10

Problem:
- Worker 1-3 take msg1-A, msg2-A, msg3-A from queue
- Worker 1 acquires lock for group-A, starts processing
- Worker 2 BLOCKS waiting for group-A lock (holding msg2-A)
- Worker 3 BLOCKS waiting for group-A lock (holding msg3-A)
- Workers 4-10 are idle (queue is empty)
- msg1-B sits in queue, unprocessed
Result: Only 1 worker active, 2 blocked, 7 idle
```

### Our Solution
**Scenario:** Per-group queues with tryAcquire()
```
Group Queues:
  group-A → [msg1, msg2, msg3]
  group-B → [msg1]
Workers: 10

Solution:
- Worker 1 finds group-A, tryAcquire() succeeds, polls msg1-A
- Worker 2 finds group-A, tryAcquire() fails, skips to group-B
- Worker 2 finds group-B, tryAcquire() succeeds, polls msg1-B
- Worker 1 processes msg1-A (group-A locked)
- Worker 2 processes msg1-B (group-B locked) ← CONCURRENT!
Result: 2 workers active, 0 blocked, 8 ready for more groups
```

## Test Categories

### 1. Core Functionality Tests

#### Test 1.1: FIFO Ordering Within Single Message Group
**Objective:** Verify messages within the same group process in FIFO order

**Setup:**
- Pool: concurrency=10, queueCapacity=100
- Messages: 20 messages for "group-1" (msg-0 through msg-19)
- Processing: Record completion order with timestamps

**Expected Behavior:**
```
Completion order MUST be: msg-0, msg-1, msg-2, ..., msg-19
Even though we have 10 workers, only 1 processes at a time per group
```

**Assertions:**
```java
List<String> completionOrder = new CopyOnWriteArrayList<>();

// Submit messages
for (int i = 0; i < 20; i++) {
    MessagePointer msg = createMessage("msg-" + i, "POOL", "group-1");
    pool.submit(msg);
}

// Wait for completion
await().atMost(5, SECONDS).until(() -> completionOrder.size() == 20);

// Verify FIFO order
for (int i = 0; i < 20; i++) {
    assertEquals("msg-" + i, completionOrder.get(i),
        "Message at index " + i + " out of order");
}

// Verify timestamps are monotonically increasing (strict FIFO)
for (int i = 1; i < completionTimestamps.size(); i++) {
    assertTrue(completionTimestamps.get(i) >= completionTimestamps.get(i-1),
        "Timestamps should be monotonically increasing");
}
```

**Pass Criteria:**
- ✅ All 20 messages process in exact FIFO order
- ✅ No message overtakes another from same group
- ✅ Timestamps confirm sequential processing

---

#### Test 1.2: Concurrent Processing Across Different Message Groups
**Objective:** Verify different message groups process concurrently

**Setup:**
- Pool: concurrency=10, queueCapacity=100
- Messages: 10 messages each for "group-1" and "group-2" (20 total)
- Processing: Track which groups are processing concurrently
- Slow processing: 100ms delay per message

**Expected Behavior:**
```
With 100ms processing and 20 messages:
- Serial processing: 2000ms total
- Concurrent (2 groups): ~1000ms total (both groups process in parallel)
```

**Assertions:**
```java
Set<String> concurrentGroups = ConcurrentHashMap.newKeySet();
AtomicInteger group1Processing = new AtomicInteger(0);
AtomicInteger group2Processing = new AtomicInteger(0);

when(mockMediator.process(any())).thenAnswer(inv -> {
    MessagePointer msg = inv.getArgument(0);
    String group = msg.messageGroupId();

    // Track concurrent processing
    if (group.equals("group-1")) {
        int concurrent = group1Processing.incrementAndGet();
        assertEquals(1, concurrent, "Only 1 worker should process group-1 at a time");
    } else if (group.equals("group-2")) {
        int concurrent = group2Processing.incrementAndGet();
        assertEquals(1, concurrent, "Only 1 worker should process group-2 at a time");
    }

    concurrentGroups.add(group);
    Thread.sleep(100); // Simulate work

    if (group.equals("group-1")) group1Processing.decrementAndGet();
    if (group.equals("group-2")) group2Processing.decrementAndGet();

    return MediationResult.SUCCESS;
});

long startTime = System.currentTimeMillis();

// Submit 10 messages for each group
for (int i = 0; i < 10; i++) {
    pool.submit(createMessage("msg-" + i, "POOL", "group-1"));
    pool.submit(createMessage("msg-" + i, "POOL", "group-2"));
}

await().atMost(2, SECONDS).until(() -> processedCount.get() == 20);
long duration = System.currentTimeMillis() - startTime;

// Verify concurrent processing happened
assertTrue(concurrentGroups.size() >= 2, "Both groups should have processed");
assertTrue(duration < 1500, "Should complete in ~1000ms with concurrency, not 2000ms serial");
```

**Pass Criteria:**
- ✅ Both groups process concurrently
- ✅ Total time < 1500ms (proves parallel processing)
- ✅ Never more than 1 worker per group at a time
- ✅ Both groups make forward progress simultaneously

---

#### Test 1.3: Mixed Interleaved Message Groups
**Objective:** Verify correct routing and ordering with interleaved messages

**Setup:**
- Pool: concurrency=10
- Messages: Interleave 3 groups (A, B, C) - 30 messages total
  ```
  Submission order: A0, B0, C0, A1, B1, C1, A2, B2, C2, ...
  ```

**Expected Behavior:**
```
Group A completion: A0, A1, A2, ..., A9 (FIFO within group)
Group B completion: B0, B1, B2, ..., B9 (FIFO within group)
Group C completion: C0, C1, C2, ..., C9 (FIFO within group)

Overall completion: May interleave, but within each group must be FIFO
Example valid: A0, B0, A1, C0, B1, A2, C1, ...
Example invalid: A1, A0 (violates group A FIFO)
```

**Assertions:**
```java
Map<String, List<String>> completionsByGroup = new ConcurrentHashMap<>();
completionsByGroup.put("group-A", new CopyOnWriteArrayList<>());
completionsByGroup.put("group-B", new CopyOnWriteArrayList<>());
completionsByGroup.put("group-C", new CopyOnWriteArrayList<>());

// Submit interleaved
for (int i = 0; i < 10; i++) {
    pool.submit(createMessage("A-" + i, "POOL", "group-A"));
    pool.submit(createMessage("B-" + i, "POOL", "group-B"));
    pool.submit(createMessage("C-" + i, "POOL", "group-C"));
}

await().atMost(5, SECONDS).until(() ->
    completionsByGroup.values().stream().mapToInt(List::size).sum() == 30);

// Verify FIFO within each group
for (String group : completionsByGroup.keySet()) {
    List<String> completions = completionsByGroup.get(group);
    assertEquals(10, completions.size());

    String prefix = group.equals("group-A") ? "A" :
                    group.equals("group-B") ? "B" : "C";

    for (int i = 0; i < 10; i++) {
        assertEquals(prefix + "-" + i, completions.get(i),
            "Messages in " + group + " out of order");
    }
}
```

**Pass Criteria:**
- ✅ Each group maintains strict FIFO order
- ✅ Groups process concurrently (interleaved completion)
- ✅ All 30 messages complete
- ✅ No messages lost or duplicated

---

### 2. Worker Utilization Tests

#### Test 2.1: Workers Don't Block on Locked Groups
**Objective:** Verify workers skip locked groups instead of blocking

**Setup:**
- Pool: concurrency=5
- Messages:
  - 10 messages for "group-slow" (500ms processing each)
  - 10 messages for "group-fast" (10ms processing each)
- Submit "group-slow" messages first, then "group-fast"

**Expected Behavior:**
```
Time 0ms: Submit 10 slow messages (group-slow)
Time 10ms: Submit 10 fast messages (group-fast)

Worker allocation:
- Worker 1: Locks group-slow, processes slow-1 (500ms)
- Workers 2-4: Try group-slow (locked), skip to group-fast
- Workers 2-4: Process fast-1, fast-2, fast-3 concurrently (10ms each)
- By time 100ms: All fast messages done
- By time 5000ms: All slow messages done

Result: Fast messages don't wait for slow group to finish
```

**Assertions:**
```java
AtomicInteger slowProcessing = new AtomicInteger(0);
AtomicInteger fastProcessing = new AtomicInteger(0);
List<Long> fastCompletionTimes = new CopyOnWriteArrayList<>();

when(mockMediator.process(any())).thenAnswer(inv -> {
    MessagePointer msg = inv.getArgument(0);
    long startTime = System.currentTimeMillis();

    if (msg.messageGroupId().equals("group-slow")) {
        int active = slowProcessing.incrementAndGet();
        assertEquals(1, active, "Only 1 worker should process slow group");
        Thread.sleep(500);
        slowProcessing.decrementAndGet();
    } else {
        int active = fastProcessing.incrementAndGet();
        assertTrue(active <= 4, "Up to 4 workers can process fast messages");
        Thread.sleep(10);
        fastCompletionTimes.add(System.currentTimeMillis() - startTime);
        fastProcessing.decrementAndGet();
    }

    return MediationResult.SUCCESS;
});

long testStart = System.currentTimeMillis();

// Submit slow messages first
for (int i = 0; i < 10; i++) {
    pool.submit(createMessage("slow-" + i, "POOL", "group-slow"));
}

// Small delay, then submit fast messages
Thread.sleep(10);
for (int i = 0; i < 10; i++) {
    pool.submit(createMessage("fast-" + i, "POOL", "group-fast"));
}

// Wait for all fast messages to complete
await().atMost(2, SECONDS).until(() -> fastCompletionTimes.size() == 10);
long fastCompleted = System.currentTimeMillis() - testStart;

// Verify fast messages completed quickly (not blocked by slow group)
assertTrue(fastCompleted < 500,
    "Fast messages should complete in < 500ms, not wait for slow group. Took: " + fastCompleted);

// Verify concurrent processing of fast messages
long maxFastProcessing = fastProcessing.get();
assertTrue(maxFastProcessing > 1,
    "Multiple workers should process fast messages concurrently");
```

**Pass Criteria:**
- ✅ Fast messages complete in < 500ms (don't wait for slow group)
- ✅ Multiple workers process fast messages simultaneously
- ✅ Only 1 worker processes slow group at a time
- ✅ No workers blocked waiting for locks

---

#### Test 2.2: High Worker Utilization with Many Groups
**Objective:** Verify all workers stay busy with sufficient message groups

**Setup:**
- Pool: concurrency=10
- Messages: 100 messages across 10 different groups (10 per group)
- Processing: 50ms per message

**Expected Behavior:**
```
With 10 groups and 10 workers:
- All 10 workers should be active simultaneously
- Each worker processes a different group
- Worker utilization: 90%+ (all busy most of the time)

Total time: ~500ms (100 messages × 50ms / 10 workers)
```

**Assertions:**
```java
AtomicInteger activeWorkers = new AtomicInteger(0);
AtomicInteger maxConcurrentWorkers = new AtomicInteger(0);
Set<String> groupsProcessingConcurrently = ConcurrentHashMap.newKeySet();

when(mockMediator.process(any())).thenAnswer(inv -> {
    MessagePointer msg = inv.getArgument(0);

    int active = activeWorkers.incrementAndGet();
    maxConcurrentWorkers.updateAndGet(max -> Math.max(max, active));
    groupsProcessingConcurrently.add(msg.messageGroupId());

    Thread.sleep(50);

    activeWorkers.decrementAndGet();
    return MediationResult.SUCCESS;
});

long startTime = System.currentTimeMillis();

// Submit 100 messages across 10 groups
for (int g = 0; g < 10; g++) {
    for (int m = 0; m < 10; m++) {
        pool.submit(createMessage("msg-" + m, "POOL", "group-" + g));
    }
}

await().atMost(2, SECONDS).until(() -> processedCount.get() == 100);
long duration = System.currentTimeMillis() - startTime;

// Verify high concurrency achieved
assertTrue(maxConcurrentWorkers.get() >= 8,
    "Should achieve at least 80% worker utilization (8/10 workers). Got: " + maxConcurrentWorkers.get());

// Verify groups processed concurrently
assertTrue(groupsProcessingConcurrently.size() >= 8,
    "At least 8 groups should have processed concurrently");

// Verify efficient total time (proves parallelism)
assertTrue(duration < 1000,
    "Should complete in < 1000ms with high concurrency. Took: " + duration);
```

**Pass Criteria:**
- ✅ Max concurrent workers ≥ 8 (80% utilization)
- ✅ At least 8 groups process concurrently
- ✅ Total time < 1000ms (proves parallel processing)
- ✅ No workers sitting idle while work available

---

### 3. Edge Case Tests

#### Test 3.1: Messages Without messageGroupId (Backward Compatibility)
**Objective:** Verify null messageGroupId uses DEFAULT_GROUP and maintains FIFO

**Setup:**
- Pool: concurrency=10
- Messages: 10 messages with messageGroupId=null

**Expected Behavior:**
```
All messages route to DEFAULT_GROUP
Process sequentially (FIFO order)
Only 1 worker active at a time (single group lock)
```

**Assertions:**
```java
List<String> completionOrder = new CopyOnWriteArrayList<>();

for (int i = 0; i < 10; i++) {
    MessagePointer msg = new MessagePointer(
        "msg-" + i, "POOL", "token", MediationType.HTTP, "http://test",
        null  // No messageGroupId
    );
    pool.submit(msg);
}

await().atMost(2, SECONDS).until(() -> completionOrder.size() == 10);

// Verify FIFO order
for (int i = 0; i < 10; i++) {
    assertEquals("msg-" + i, completionOrder.get(i));
}
```

**Pass Criteria:**
- ✅ All messages process in FIFO order
- ✅ No errors with null messageGroupId
- ✅ Backward compatible with existing code

---

#### Test 3.2: Single Message Group (Serialization)
**Objective:** Verify single group processes serially despite high concurrency

**Setup:**
- Pool: concurrency=100 (high concurrency)
- Messages: 50 messages all in "group-single"
- Processing: Track max concurrent workers for this group

**Expected Behavior:**
```
Despite 100 workers available:
- Only 1 worker processes "group-single" at a time
- Effective concurrency = 1
- Messages process in strict FIFO order
```

**Assertions:**
```java
AtomicInteger activeInGroup = new AtomicInteger(0);
AtomicInteger maxConcurrentInGroup = new AtomicInteger(0);

when(mockMediator.process(any())).thenAnswer(inv -> {
    int active = activeInGroup.incrementAndGet();
    maxConcurrentInGroup.updateAndGet(max -> Math.max(max, active));

    Thread.sleep(10);

    activeInGroup.decrementAndGet();
    return MediationResult.SUCCESS;
});

for (int i = 0; i < 50; i++) {
    pool.submit(createMessage("msg-" + i, "POOL", "group-single"));
}

await().atMost(2, SECONDS).until(() -> processedCount.get() == 50);

// Verify only 1 worker ever processed this group
assertEquals(1, maxConcurrentInGroup.get(),
    "Only 1 worker should process single group at a time, even with concurrency=100");
```

**Pass Criteria:**
- ✅ Max concurrent workers for group = 1
- ✅ All messages process in FIFO order
- ✅ High pool concurrency doesn't violate group FIFO

---

#### Test 3.3: Many Message Groups (Scalability)
**Objective:** Verify system handles large number of message groups efficiently

**Setup:**
- Pool: concurrency=100
- Messages: 1000 messages across 1000 different groups (1 per group)
- Processing: 10ms per message

**Expected Behavior:**
```
With 1000 groups and 100 workers:
- Up to 100 groups process concurrently
- Total time: ~100ms (1000 messages × 10ms / 100 workers)
- Memory: Creates 1000 queues + 1000 locks (~200KB overhead)
```

**Assertions:**
```java
Set<String> groupsCreated = ConcurrentHashMap.newKeySet();

when(mockMediator.process(any())).thenAnswer(inv -> {
    MessagePointer msg = inv.getArgument(0);
    groupsCreated.add(msg.messageGroupId());
    Thread.sleep(10);
    return MediationResult.SUCCESS;
});

long startTime = System.currentTimeMillis();

// Submit 1 message per group for 1000 groups
for (int i = 0; i < 1000; i++) {
    pool.submit(createMessage("msg-0", "POOL", "group-" + i));
}

await().atMost(2, SECONDS).until(() -> processedCount.get() == 1000);
long duration = System.currentTimeMillis() - startTime;

// Verify all groups created
assertEquals(1000, groupsCreated.size());

// Verify efficient processing (proves high concurrency)
assertTrue(duration < 300,
    "1000 messages should complete in < 300ms with 100 workers. Took: " + duration);
```

**Pass Criteria:**
- ✅ Successfully creates 1000 message groups
- ✅ All 1000 messages complete
- ✅ Total time < 300ms (proves parallelism)
- ✅ No memory leaks or crashes

---

### 4. Stress Tests

#### Test 4.1: High Throughput Under Load
**Objective:** Verify system maintains FIFO and concurrency under sustained high load

**Setup:**
- Pool: concurrency=50
- Messages: 10,000 messages across 100 groups (100 per group)
- Processing: 5ms per message
- Run time: Continuous submission

**Expected Behavior:**
```
Throughput: ~10,000 messages/second
All groups maintain FIFO order
No message loss or duplication
Worker utilization: 95%+
```

**Assertions:**
```java
Map<String, List<Integer>> completionsByGroup = new ConcurrentHashMap<>();
AtomicInteger totalProcessed = new AtomicInteger(0);

// Initialize tracking for 100 groups
for (int g = 0; g < 100; g++) {
    completionsByGroup.put("group-" + g, new CopyOnWriteArrayList<>());
}

when(mockMediator.process(any())).thenAnswer(inv -> {
    MessagePointer msg = inv.getArgument(0);
    String group = msg.messageGroupId();
    int messageNum = Integer.parseInt(msg.id().split("-")[1]);

    completionsByGroup.get(group).add(messageNum);
    totalProcessed.incrementAndGet();

    Thread.sleep(5);
    return MediationResult.SUCCESS;
});

long startTime = System.currentTimeMillis();

// Submit 10,000 messages
for (int g = 0; g < 100; g++) {
    for (int m = 0; m < 100; m++) {
        pool.submit(createMessage("msg-" + m, "POOL", "group-" + g));
    }
}

await().atMost(10, SECONDS).until(() -> totalProcessed.get() == 10000);
long duration = System.currentTimeMillis() - startTime;

// Verify throughput
double messagesPerSecond = (10000.0 / duration) * 1000;
assertTrue(messagesPerSecond >= 5000,
    "Should achieve at least 5000 msg/s. Got: " + messagesPerSecond);

// Verify FIFO in all groups
for (int g = 0; g < 100; g++) {
    List<Integer> completions = completionsByGroup.get("group-" + g);
    assertEquals(100, completions.size());

    // Verify FIFO order
    for (int i = 0; i < 100; i++) {
        assertEquals(i, completions.get(i).intValue(),
            "Group " + g + " has messages out of order");
    }
}

// Verify no message loss
assertEquals(10000, totalProcessed.get());
```

**Pass Criteria:**
- ✅ Throughput ≥ 5000 messages/second
- ✅ All 100 groups maintain FIFO order
- ✅ All 10,000 messages processed
- ✅ No timeouts or deadlocks

---

#### Test 4.2: Concurrent Submission and Processing
**Objective:** Verify thread safety with concurrent submissions

**Setup:**
- Pool: concurrency=50
- Submitters: 10 threads submitting concurrently
- Messages: Each thread submits 100 messages to its own group
- Total: 1000 messages across 10 groups

**Expected Behavior:**
```
All submissions accepted
No race conditions
All messages processed
Each group maintains FIFO order
```

**Assertions:**
```java
ExecutorService submitters = Executors.newFixedThreadPool(10);
Map<String, List<String>> completionsByGroup = new ConcurrentHashMap<>();
CountDownLatch startLatch = new CountDownLatch(10);
CountDownLatch doneLatch = new CountDownLatch(10);

for (int t = 0; t < 10; t++) {
    final int threadNum = t;
    String group = "group-" + threadNum;
    completionsByGroup.put(group, new CopyOnWriteArrayList<>());

    submitters.submit(() -> {
        startLatch.countDown();
        try {
            startLatch.await(); // All threads start together

            for (int i = 0; i < 100; i++) {
                pool.submit(createMessage("msg-" + i, "POOL", group));
                Thread.sleep(1); // Slight delay between submissions
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneLatch.countDown();
        }
    });
}

// Wait for all submissions
assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

// Wait for all processing
await().atMost(10, SECONDS).until(() ->
    completionsByGroup.values().stream().mapToInt(List::size).sum() == 1000);

// Verify FIFO in each group
for (int g = 0; g < 10; g++) {
    List<String> completions = completionsByGroup.get("group-" + g);
    assertEquals(100, completions.size());

    for (int i = 0; i < 100; i++) {
        assertEquals("msg-" + i, completions.get(i));
    }
}
```

**Pass Criteria:**
- ✅ All 1000 messages submitted successfully
- ✅ All 1000 messages processed
- ✅ No ConcurrentModificationException
- ✅ Each group maintains FIFO order

---

### 5. Performance Regression Tests

#### Test 5.1: Performance Comparison - Single Group vs Multiple Groups
**Objective:** Demonstrate performance improvement with multiple groups

**Setup:**
- Pool: concurrency=10
- Scenario A: 100 messages in 1 group
- Scenario B: 100 messages in 10 groups (10 per group)
- Processing: 50ms per message

**Expected Behavior:**
```
Scenario A (single group): ~5000ms (100 × 50ms / 1 worker)
Scenario B (10 groups): ~500ms (100 × 50ms / 10 workers)
Speedup: 10x
```

**Assertions:**
```java
// Scenario A: Single group
long startA = System.currentTimeMillis();
for (int i = 0; i < 100; i++) {
    pool.submit(createMessage("msg-" + i, "POOL", "group-single"));
}
await().atMost(10, SECONDS).until(() -> processedCount.get() == 100);
long durationA = System.currentTimeMillis() - startA;

// Reset
processedCount.set(0);

// Scenario B: 10 groups
long startB = System.currentTimeMillis();
for (int g = 0; g < 10; g++) {
    for (int m = 0; m < 10; m++) {
        pool.submit(createMessage("msg-" + m, "POOL", "group-" + g));
    }
}
await().atMost(10, SECONDS).until(() -> processedCount.get() == 100);
long durationB = System.currentTimeMillis() - startB;

// Verify speedup
double speedup = (double) durationA / durationB;
assertTrue(speedup >= 8.0,
    String.format("Expected 8-10x speedup with 10 groups. Got %.1fx (A=%dms, B=%dms)",
        speedup, durationA, durationB));
```

**Pass Criteria:**
- ✅ Single group: 4500-5500ms
- ✅ 10 groups: 500-700ms
- ✅ Speedup: 8-10x

---

## Test Implementation Guide

### Test Class Structure

```java
@QuarkusTest
class MessageGroupFifoOrderingIntegrationTest {

    @Inject
    QueueManager queueManager;

    @InjectMock
    Mediator mockMediator;

    @InjectMock
    PoolMetricsService mockPoolMetrics;

    @InjectMock
    WarningService mockWarningService;

    private ProcessPool pool;
    private ConcurrentMap<String, MessagePointer> inPipelineMap;

    @BeforeEach
    void setup() {
        inPipelineMap = new ConcurrentHashMap<>();
        pool = createTestPool("TEST-POOL", 10, 100);
        pool.start();
    }

    @AfterEach
    void teardown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    private ProcessPool createTestPool(String poolCode, int concurrency, int queueCapacity) {
        return new ProcessPoolImpl(
            poolCode,
            concurrency,
            queueCapacity,
            null, // No rate limiting
            mockMediator,
            queueManager,
            inPipelineMap,
            mockPoolMetrics,
            mockWarningService
        );
    }

    // Test methods here...
}
```

### Helper Methods

```java
private MessagePointer createMessage(String id, String poolCode, String messageGroupId) {
    return new MessagePointer(
        id,
        poolCode,
        "test-token",
        MediationType.HTTP,
        "http://localhost:8080/test",
        messageGroupId
    );
}

private void recordCompletion(String messageId, String messageGroupId,
                               Map<String, List<String>> completionsByGroup) {
    completionsByGroup.computeIfAbsent(messageGroupId, k -> new CopyOnWriteArrayList<>())
        .add(messageId);
}

private void verifyFifoOrder(List<String> completions, String prefix) {
    for (int i = 0; i < completions.size(); i++) {
        assertEquals(prefix + i, completions.get(i),
            "Message at index " + i + " out of order");
    }
}
```

## Test Execution Plan

### Phase 1: Core Functionality (Week 1)
- Test 1.1: FIFO within group
- Test 1.2: Concurrent across groups
- Test 1.3: Mixed interleaved groups
- Test 3.1: Backward compatibility

### Phase 2: Worker Utilization (Week 1)
- Test 2.1: No blocking on locked groups
- Test 2.2: High worker utilization

### Phase 3: Edge Cases (Week 2)
- Test 3.2: Single group serialization
- Test 3.3: Many groups scalability

### Phase 4: Stress Testing (Week 2)
- Test 4.1: High throughput
- Test 4.2: Concurrent submission

### Phase 5: Performance (Week 2)
- Test 5.1: Performance comparison

## Success Criteria

All tests must pass with:
- ✅ 100% FIFO ordering within groups
- ✅ Verified concurrent processing across groups
- ✅ No worker blocking on locks
- ✅ Worker utilization ≥ 80% with sufficient groups
- ✅ Throughput ≥ 5000 messages/second under load
- ✅ No deadlocks, race conditions, or memory leaks
- ✅ Backward compatible with null messageGroupId

## Test Metrics

Track these metrics across all tests:
- FIFO violations: 0
- Deadlocks: 0
- Race condition failures: 0
- Memory leaks: 0
- Average worker utilization: ≥ 80%
- Throughput (messages/second): ≥ 5000
- Max concurrent groups: Limited only by concurrency setting
