# Async SQS Consumer Implementation Plan

## Goal
Implement async SQS consumer using `SqsAsyncClient` with `CompletableFuture` to process messages as they arrive during long polling, reducing latency in test/staging environments.

## Current Architecture

### Synchronous Flow (SqsQueueConsumer)
```
Thread 1: [Poll 20s] → [Receive 0-10 msgs] → [Process batch] → [Poll 20s]...
Thread 2: [Poll 20s] → [Receive 0-10 msgs] → [Process batch] → [Poll 20s]...
Thread N: [Poll 20s] → [Receive 0-10 msgs] → [Process batch] → [Poll 20s]...
```

**Issue**: If 1 message arrives at t=0s and poll waits until t=20s, that message sits idle for 20s before processing starts.

### Proposed Async Flow (AsyncSqsQueueConsumer)
```
Async Loop: [Start Poll] → [Msg arrives] → [Process immediately] → [Continue polling]
            └──────────────────[CompletableFuture chain]──────────────────┘
```

**Benefit**: Messages are processed as soon as they arrive, not after the full poll completes.

---

## Implementation Strategy: Minimal Disruption Approach

### Option A: New Async Consumer Class (RECOMMENDED)
✅ **Pros**:
- Zero risk to existing sync implementation
- Can toggle between sync/async via config
- Easy rollback if issues arise
- Both implementations available for comparison

❌ **Cons**:
- Slightly more code to maintain
- Need factory logic to choose consumer type

### Option B: Replace Existing SqsQueueConsumer
❌ **Not Recommended**: Breaking change, no fallback option

---

## Detailed Implementation Plan

### 1. Add Configuration Property
**File**: `src/main/resources/application.properties`

```properties
# SQS Consumer Mode (SYNC or ASYNC)
message-router.sqs.consumer-mode=ASYNC

# For async mode: number of concurrent polling tasks (default: connections * 2)
message-router.sqs.async-polling-concurrency=${message-router.sqs.connections:10}
```

**Reasoning**:
- Default to ASYNC for better performance
- Keep SYNC available for compatibility/debugging
- Async concurrency allows multiple polls in flight

---

### 2. Create AsyncSqsQueueConsumer Class
**File**: `src/main/java/tech/flowcatalyst/messagerouter/consumer/AsyncSqsQueueConsumer.java`

**Key Design Points**:

```java
public class AsyncSqsQueueConsumer extends AbstractQueueConsumer {
    private final SqsAsyncClient sqsAsyncClient;
    private final List<CompletableFuture<Void>> activePolls;
    private final int pollingConcurrency;

    // Core async polling loop
    @Override
    protected void consumeMessages() {
        // Start N concurrent polling loops
        for (int i = 0; i < pollingConcurrency; i++) {
            submitAsyncPoll();
        }

        // Keep main thread alive until shutdown
        while (running.get()) {
            Thread.sleep(1000);
        }
    }

    private void submitAsyncPoll() {
        CompletableFuture<ReceiveMessageResponse> pollFuture =
            sqsAsyncClient.receiveMessage(receiveRequest);

        pollFuture
            .thenAccept(response -> {
                // Process messages immediately as they arrive
                response.messages().forEach(msg ->
                    processMessage(msg.body(), new SqsMessageCallback(msg.receiptHandle()))
                );
            })
            .thenRun(() -> {
                // Continue polling if still running
                if (running.get()) {
                    submitAsyncPoll(); // Chain next poll
                }
            })
            .exceptionally(ex -> {
                LOG.error("Error in async poll", ex);
                if (running.get()) {
                    // Backoff and retry
                    scheduler.schedule(() -> submitAsyncPoll(), 1, TimeUnit.SECONDS);
                }
                return null;
            });
    }
}
```

**Key Features**:
- ✅ Multiple concurrent polls using CompletableFuture chains
- ✅ Process each message immediately when received
- ✅ Automatic poll chaining - new poll starts after current completes
- ✅ Graceful error handling with backoff
- ✅ Clean shutdown by breaking the chain when `running=false`

---

### 3. Update Factory to Support Async Mode
**File**: `src/main/java/tech/flowcatalyst/messagerouter/factory/QueueConsumerFactoryImpl.java`

```java
@ConfigProperty(name = "message-router.sqs.consumer-mode", defaultValue = "ASYNC")
SqsConsumerMode sqsConsumerMode;

@Inject
SqsClient sqsClient;

@Inject
SqsAsyncClient sqsAsyncClient; // Auto-provided by Quarkus

@Override
public QueueConsumer createConsumer(QueueConfig queueConfig, int connections) {
    return switch (queueType) {
        case SQS -> {
            String queueUrl = queueConfig.queueUri() != null
                ? queueConfig.queueUri()
                : queueConfig.queueName();

            if (sqsConsumerMode == SqsConsumerMode.ASYNC) {
                yield new AsyncSqsQueueConsumer(
                    sqsAsyncClient,
                    queueUrl,
                    connections,
                    queueManager,
                    queueMetrics,
                    warningService,
                    sqsMaxMessagesPerPoll,
                    sqsWaitTimeSeconds,
                    metricsPollIntervalSeconds
                );
            } else {
                yield new SqsQueueConsumer(
                    sqsClient,
                    queueUrl,
                    connections,
                    queueManager,
                    queueMetrics,
                    warningService,
                    sqsMaxMessagesPerPoll,
                    sqsWaitTimeSeconds,
                    metricsPollIntervalSeconds
                );
            }
        }
        case ACTIVEMQ -> // unchanged
    };
}
```

---

### 4. Create SqsConsumerMode Enum
**File**: `src/main/java/tech/flowcatalyst/messagerouter/config/SqsConsumerMode.java`

```java
public enum SqsConsumerMode {
    SYNC,   // Traditional blocking polls
    ASYNC   // CompletableFuture-based async polls
}
```

---

### 5. Update AbstractQueueConsumer for Async Support
**File**: `src/main/java/tech/flowcatalyst/messagerouter/consumer/AbstractQueueConsumer.java`

**Current**: `executorService.submit(this::consumeMessages)` called N times

**Change**: Allow subclasses to control threading

```java
@Override
public void start() {
    if (running.compareAndSet(false, true)) {
        LOG.infof("Starting consumer for queue [%s] with %d connections",
            getQueueIdentifier(), connections);

        startConsumption(); // Let subclass control how to start

        // Metrics polling remains the same
        executorService.submit(this::pollQueueMetrics);
    }
}

// Default sync implementation
protected void startConsumption() {
    for (int i = 0; i < connections; i++) {
        executorService.submit(this::consumeMessages);
    }
}
```

**AsyncSqsQueueConsumer** overrides:
```java
@Override
protected void startConsumption() {
    // Single thread that manages async CompletableFuture chains
    executorService.submit(this::consumeMessages);
}
```

---

### 6. Shutdown Handling for Async Consumer

**Challenge**: CompletableFutures continue running after `stop()` called

**Solution**: Track active futures and await completion

```java
private final List<CompletableFuture<Void>> activePolls =
    new CopyOnWriteArrayList<>();

@Override
public void stop() {
    LOG.info("Stopping async consumer - waiting for active polls to complete");
    running.set(false);

    // Wait for all active polls to complete (with timeout)
    CompletableFuture.allOf(activePolls.toArray(new CompletableFuture[0]))
        .orTimeout(30, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            LOG.warn("Timeout waiting for polls to complete, forcing shutdown");
            return null;
        })
        .join();

    executorService.shutdown();
}
```

---

### 7. Testing Strategy

#### Unit Tests
**File**: `src/test/java/tech/flowcatalyst/messagerouter/consumer/AsyncSqsQueueConsumerTest.java`

Test cases:
- ✅ Messages processed immediately as they arrive
- ✅ Multiple concurrent polls in flight
- ✅ Graceful shutdown cancels polling chains
- ✅ Error handling with backoff and retry
- ✅ Heartbeat updates during async polling

#### Integration Tests
**File**: `src/test/java/tech/flowcatalyst/messagerouter/integration/AsyncSqsIntegrationTest.java`

Test with LocalStack:
- ✅ Measure latency: time from message sent to processing started
- ✅ Compare sync vs async latency under various loads
- ✅ Verify message ordering (if FIFO queue)
- ✅ Verify no message loss during shutdown

#### Configuration Test
```properties
# Test both modes work
%test.message-router.sqs.consumer-mode=ASYNC
```

---

### 8. Metrics and Observability

Add async-specific metrics:

```java
// In AsyncSqsQueueConsumer
private void recordAsyncMetrics() {
    meterRegistry.gauge("sqs.async.active_polls", activePolls.size());
    meterRegistry.gauge("sqs.async.polling_concurrency", pollingConcurrency);
}
```

Add to `/metrics` endpoint:
- `sqs_async_active_polls` - number of polls currently in flight
- `sqs_async_processing_latency` - time from receive to process start

---

## Migration Path

### Phase 1: Development (Week 1)
1. Create `AsyncSqsQueueConsumer` class
2. Add configuration properties
3. Update factory with mode selection
4. Write unit tests

### Phase 2: Integration Testing (Week 1-2)
1. Test with LocalStack in dev environment
2. Measure latency improvements
3. Load testing with high message volume
4. Verify no regressions

### Phase 3: Staging Deployment (Week 2)
1. Deploy to staging with `ASYNC` mode enabled
2. Monitor metrics and error rates
3. Compare performance with prod (SYNC mode)

### Phase 4: Production Rollout (Week 3)
1. Deploy to prod with `SYNC` mode (no change)
2. Gradually enable `ASYNC` mode for low-priority queues
3. Monitor and validate
4. Roll out to all queues if successful

### Rollback Plan
If issues arise, change config:
```properties
message-router.sqs.consumer-mode=SYNC
```
Restart application - immediate rollback to proven sync implementation.

---

## Performance Expectations

### Current Sync Performance
- **Best case**: Message arrives at t=0s, processed at t=0s (lucky timing)
- **Worst case**: Message arrives at t=0s, poll waits until t=20s, processed at t=20s
- **Average**: ~10s latency from arrival to processing

### Expected Async Performance
- **Best case**: Message arrives, processed immediately (~100ms latency)
- **Worst case**: Message arrives during SQS internal batching (~1s latency)
- **Average**: ~500ms latency from arrival to processing

**Expected improvement**: ~20x latency reduction for time-sensitive messages

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Async client has different error modes | Medium | Comprehensive error handling in exceptionally() |
| Message ordering issues (FIFO queues) | High | Test with FIFO queues, document ordering guarantees |
| Increased resource usage (more concurrent polls) | Medium | Make polling concurrency configurable |
| Shutdown doesn't wait for inflight polls | High | Implement proper shutdown with timeout |
| SqsAsyncClient not available in Quarkus | Low | Check Quarkus docs, may need explicit dependency |

---

## Dependencies

### Already Available
- ✅ `quarkus-amazon-sqs` - includes both sync and async clients
- ✅ `AbstractQueueConsumer` - reusable base class
- ✅ Existing test infrastructure with LocalStack

### To Add
None - Quarkus SQS extension auto-provides `SqsAsyncClient`

---

## Files to Create/Modify

### New Files (4)
1. `AsyncSqsQueueConsumer.java` - Async consumer implementation
2. `SqsConsumerMode.java` - Enum for sync/async mode
3. `AsyncSqsQueueConsumerTest.java` - Unit tests
4. `AsyncSqsIntegrationTest.java` - Integration tests

### Modified Files (3)
1. `application.properties` - Add async mode config
2. `QueueConsumerFactoryImpl.java` - Add mode selection logic
3. `AbstractQueueConsumer.java` - Make startConsumption() overridable

**Total**: 7 files, ~500 lines of new code

---

## Summary

This plan provides:
- ✅ **Minimal disruption**: New class, existing sync code unchanged
- ✅ **Easy rollback**: Config flag to switch modes
- ✅ **Significant performance gain**: ~20x latency reduction
- ✅ **Production-ready**: Proper error handling, shutdown, metrics
- ✅ **Well-tested**: Comprehensive unit and integration tests

The async implementation processes messages as they arrive rather than waiting for the full long-poll duration, dramatically reducing latency for time-sensitive workloads while maintaining all existing functionality.
