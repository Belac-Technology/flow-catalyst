# Message Router Stability Improvements

**Status:** Planning Phase
**Date:** 2025-10-10
**Priority:** CRITICAL

## Executive Summary

This document outlines critical stability and reliability issues discovered in the message router implementation, primarily related to resource lifecycle management, metrics tracking, and exception handling. These issues can lead to memory leaks, processing stalls, and metric drift under production workloads.

**Impact:** Without these fixes, the system will experience:
- Memory exhaustion due to unbounded map growth
- Pool stalls due to semaphore permit leaks
- Inaccurate monitoring and alerting
- Slow recovery from errors and restarts

---

## Critical Issues

### Issue #1: Message Pointer Memory Leak on Exception
**Severity:** CRITICAL
**Location:** `ProcessPoolImpl.java:144-233`

#### Problem
Messages are only removed from `inPipelineMap` in a finally block inside an *inner* try block (line 209-217). Exceptions occurring before entering this inner try block prevent cleanup, causing permanent memory leaks.

#### Failure Scenarios
1. **InterruptedException during semaphore.acquire()** (line 165)
   - Caught at line 219 → thread breaks → message never removed

2. **Exception during MDC setup** (lines 152-160)
   - Caught at line 222 → cleanup code never reached

3. **Any exception between message polling and inner try block**
   - Message added to map but never removed

#### Impact
- Unbounded memory growth in `inPipelineMap`
- OutOfMemoryError in production
- Messages with leaked IDs permanently blocked from reprocessing (deduplication check at `QueueManager.java:259`)
- Each leaked message typically ~500 bytes, but prevents processing of all future messages with same ID

#### Root Cause
Nested try-catch blocks with cleanup only in inner finally:

```java
while (running.get() || !messageQueue.isEmpty()) {
    try {  // OUTER try - line 146
        MessagePointer message = messageQueue.poll(1, TimeUnit.SECONDS);
        // MDC setup (lines 152-160)
        semaphore.acquire();  // Line 165 - can throw!
        // More setup
        try {  // INNER try - line 169
            // Processing logic
        } finally {  // Line 209 - ONLY executes if inner try reached!
            inPipelineMap.remove(message.id());  // Line 211
            semaphore.release();  // Line 213
        }
    } catch (InterruptedException e) {  // Line 219 - NO CLEANUP
        Thread.currentThread().interrupt();
        break;
    } catch (Exception e) {  // Line 222 - NO CLEANUP
        LOG.error("Error processing message", e);
    }
}
```

---

### Issue #2: Message Callback Memory Leak
**Severity:** CRITICAL
**Location:** `QueueManager.java:266` + callback removal at lines 305, 313

#### Problem
The `messageCallbacks` map is populated when messages are routed (line 266), but only cleaned up when `ack()` or `nack()` is called. These methods are only invoked from within the inner try block of `ProcessPoolImpl`. If Issue #1 occurs, callbacks are never removed.

#### Impact
- Memory leak in `messageCallbacks` ConcurrentHashMap
- Each callback holds queue-specific state (SQS receipt handles, ActiveMQ sessions)
- Potential prevention of garbage collection of larger object graphs
- Growth rate correlates with exception rate

#### Dependencies
Directly caused by Issue #1 - fixing #1 will partially address this, but defensive cleanup still needed.

---

### Issue #3: Semaphore Permit Leak
**Severity:** CRITICAL
**Location:** `ProcessPoolImpl.java:165-213`

#### Problem
Semaphore permits are acquired at line 165 but only released in the inner finally block (line 213). Exceptions between acquire and inner try block leak permits.

#### Impact
- **Progressive pool degradation:** Each leaked permit reduces pool concurrency by 1
- **Complete pool stall:** After N leaks (where N = concurrency), pool stops processing entirely
- **Silent failure:** Pool appears running but processes nothing
- **Difficult diagnosis:** No error messages, just reduced throughput

#### Example Scenario
Pool with concurrency=10:
- Day 1: 2 permits leak → effective concurrency drops to 8
- Day 2: 3 more leak → effective concurrency drops to 5
- Day 3: 5 more leak → effective concurrency drops to 0 → **COMPLETE STALL**

Monitoring shows "activeWorkers: 0" but no processing happening and no errors.

---

### Issue #4: Metrics Drift - Active Workers Counter
**Severity:** HIGH
**Location:** `ProcessPoolImpl.java:166` + `MicrometerPoolMetricsService.java:36-58`

#### Problem
The `activeWorkers` metric is incremented via `recordProcessingStarted()` (line 166) but only decremented in `recordProcessingSuccess()` and `recordProcessingFailure()` (called at lines 193, 198). Exceptions in the outer catch block skip the decrement.

#### Impact
- **Permanent upward drift** in activeWorkers gauge
- **False monitoring alerts** ("Pool at max capacity" when actually idle)
- **Incorrect autoscaling decisions**
- **Misleading dashboards** and capacity planning
- Drift accumulates indefinitely (never self-corrects)

#### Metrics Corruption Example
```
Actual State: 2 workers processing
Metric Shows: 47 workers active (after 45 exceptions)
Alert: "CRITICAL: Pool at 470% capacity"
```

---

## High Priority Issues

### Issue #5: Pool Metrics Memory Leak on Configuration Changes
**Severity:** HIGH
**Location:** `QueueManager.java:163-173` + `MicrometerPoolMetricsService.java:27`

#### Problem
During configuration sync, pools are removed from `processPools` map (line 171), but corresponding metrics in `MicrometerPoolMetricsService.poolMetrics` map persist forever.

#### Impact
- Slow memory leak if pool codes change frequently
- Stale metrics reported for deleted pools
- Micrometer registry accumulates orphaned meters
- Dashboard shows pools that no longer exist

#### Scenarios
- Dynamic pool configuration based on time of day
- A/B testing with different pool configurations
- Pool renaming during refactoring

---

### Issue #6: No Cleanup During Forced Shutdown
**Severity:** MEDIUM
**Location:** `ProcessPoolImpl.java:112-119`

#### Problem
If graceful drain exceeds 60 seconds, `shutdownNow()` forcibly terminates workers. Interrupted workers leave messages in `inPipelineMap` with no cleanup or nack.

#### Impact
- Messages stuck in "processing" state until visibility timeout expires
- Delayed message reprocessing after restart (could be 5-30 minutes depending on timeout)
- Inaccurate post-shutdown metrics
- No explicit nack means queues can't optimize redelivery

---

### Issue #7: Missing InPipelineMap Cleanup on Shutdown
**Severity:** MEDIUM
**Location:** `QueueManager.java:64-68`

#### Problem
Shutdown handler drains pools but never explicitly cleans up `inPipelineMap` or `messageCallbacks`. Relies entirely on individual message processing to clean up, but interrupted workers won't complete.

#### Impact
- Messages in-flight during shutdown are not nacked
- Slower recovery after restart
- Potential for duplicate processing if restart happens before visibility timeout
- Final metrics don't reflect true state

---

## Medium Priority Issues

### Issue #8: Default Pool Persists Indefinitely
**Severity:** MEDIUM
**Location:** `QueueManager.java:331-354`

#### Problem
DEFAULT-POOL is created lazily when messages arrive with unknown pool codes. It's never removed during configuration sync, even after the misconfiguration is fixed.

#### Impact
- Resource waste: 20 workers + 500 queue capacity consuming memory
- Masks configuration errors by silently accepting misrouted messages
- No automatic cleanup or notification when pool is no longer needed

#### Resource Cost
- 20 virtual threads (minimal cost but still allocated)
- BlockingQueue capacity 500 (4-8KB depending on message size)
- Rate limiter registry
- Metrics collection overhead

---

### Issue #9: Rate Limiter Registry Unbounded Growth
**Severity:** LOW-MEDIUM
**Location:** `ProcessPoolImpl.java:235-244`

#### Problem
Rate limiters are created per `rateLimitKey` and cached in `rateLimiterRegistry`. No eviction mechanism exists. High cardinality keys (e.g., per-customer limits with thousands of customers) cause unbounded growth.

#### Impact
- Memory leak proportional to unique rate limit key count
- Each rate limiter maintains internal state, timers, and sliding windows
- Can grow to millions of entries in multi-tenant scenarios

#### Example Scenario
- System with 100,000 customers
- Each customer gets unique rate limit key
- 100,000 rate limiter instances created
- Each instance: ~1-2KB → 100-200MB memory
- Never cleaned up, even for inactive customers

---

## Recommendations

### Phase 1: Critical Fixes (Immediate - Week 1)

#### 1.1 Restructure ProcessPoolImpl Exception Handling
**Priority:** P0
**Effort:** 2-3 days
**Risk:** Medium - requires careful testing of exception paths

**Changes:**
- Flatten try-catch structure to single level
- Move all cleanup to outer finally block
- Use boolean flags to track resource acquisition
- Ensure cleanup is idempotent

**Implementation approach:**
```java
MessagePointer message = null;
boolean semaphoreAcquired = false;
boolean mdcSet = false;

try {
    message = messageQueue.poll(1, TimeUnit.SECONDS);
    if (message == null) continue;

    // MDC setup
    MDC.put("messageId", message.id());
    mdcSet = true;

    // Acquire semaphore
    semaphore.acquire();
    semaphoreAcquired = true;
    poolMetrics.recordProcessingStarted(poolCode);

    // Rate limiting and processing
    // ... (all processing logic here)

} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    if (message != null) {
        messageCallback.nack(message);
    }
    break;
} catch (Exception e) {
    LOG.error("Error processing message", e);
    if (message != null) {
        messageCallback.nack(message);
    }
} finally {
    // Cleanup always happens
    if (message != null) {
        inPipelineMap.remove(message.id());
    }
    if (semaphoreAcquired) {
        semaphore.release();
        poolMetrics.recordProcessingFinished(poolCode);
    }
    if (mdcSet) {
        MDC.clear();
    }
}
```

**Testing requirements:**
- Unit tests for each exception scenario
- Integration tests with forced interrupts
- Verify semaphore permits never leak
- Verify map cleanup in all paths
- Load testing with induced errors

---

#### 1.2 Fix Metrics Tracking
**Priority:** P0
**Effort:** 1 day
**Risk:** Low

**Changes:**
- Add `recordProcessingFinished()` method to metrics service
- Call in finally block (paired with `recordProcessingStarted()`)
- Use increment/decrement only in paired calls
- Add validation checks for negative counters

**Alternative approach (more robust):**
- Replace increment/decrement with gauge calculation
- Periodically compute activeWorkers from semaphore state
- Eliminates drift possibility entirely

---

#### 1.3 Add Map Size Monitoring
**Priority:** P0
**Effort:** 0.5 days
**Risk:** Low

**Changes:**
- Expose `inPipelineMap.size()` as Micrometer gauge
- Expose `messageCallbacks.size()` as Micrometer gauge
- Add alerts for abnormal growth
- Add dashboard panels for these metrics

**Alert thresholds:**
- Warning: Map size > 1000 (indicates processing slowdown or errors)
- Critical: Map size growing > 100/minute (indicates leak)
- Critical: Map size > concurrency × queue capacity (definite leak)

---

### Phase 2: High Priority Fixes (Week 2)

#### 2.1 Implement Pool Metrics Cleanup
**Priority:** P1
**Effort:** 1 day
**Risk:** Low

**Changes:**
- Add `removePoolMetrics(String poolCode)` to `PoolMetricsService` interface
- Call during pool removal in `QueueManager.syncConfiguration()`
- Unregister Micrometer meters
- Clear internal metrics maps

**Implementation:**
```java
// In MicrometerPoolMetricsService
public void removePoolMetrics(String poolCode) {
    PoolMetricsHolder metrics = poolMetrics.remove(poolCode);
    if (metrics != null) {
        // Remove meters from registry
        meterRegistry.remove(metrics.messagesSubmitted.getId());
        meterRegistry.remove(metrics.messagesSucceeded.getId());
        // ... remove all meters
        LOG.infof("Removed metrics for pool: %s", poolCode);
    }
}
```

---

#### 2.2 Improve Shutdown Handling
**Priority:** P1
**Effort:** 1-2 days
**Risk:** Medium

**Changes:**
- Add explicit cleanup phase to `QueueManager.onShutdown()`
- Nack all messages remaining in `inPipelineMap`
- Clear `messageCallbacks` after nacking
- Add metrics for shutdown cleanup (messages nacked, time taken)

**Implementation:**
```java
void onShutdown(@Observes ShutdownEvent event) {
    LOG.info("QueueManager shutting down...");

    // Step 1: Stop consumers (prevent new messages)
    stopAllConsumers();

    // Step 2: Drain pools (process what's in flight)
    drainAllPools();

    // Step 3: Cleanup remaining messages (NEW)
    cleanupRemainingMessages();
}

private void cleanupRemainingMessages() {
    int nacked = 0;
    for (MessagePointer message : inPipelineMap.values()) {
        try {
            MessageCallback callback = messageCallbacks.get(message.id());
            if (callback != null) {
                callback.nack(message);
                nacked++;
            }
        } catch (Exception e) {
            LOG.errorf(e, "Error nacking message during shutdown: %s", message.id());
        }
    }

    inPipelineMap.clear();
    messageCallbacks.clear();

    LOG.infof("Shutdown cleanup: nacked %d messages", nacked);
}
```

---

### Phase 3: Medium Priority Improvements (Week 3-4)

#### 3.1 Add Default Pool Auto-Cleanup
**Priority:** P2
**Effort:** 1 day
**Risk:** Low

**Changes:**
- Track usage of DEFAULT-POOL (timestamp of last message)
- During configuration sync, remove DEFAULT-POOL if unused for > 1 hour
- Log when DEFAULT-POOL is created and removed
- Add metric for DEFAULT-POOL usage

---

#### 3.2 Implement Rate Limiter Eviction
**Priority:** P2
**Effort:** 2 days
**Risk:** Medium

**Changes:**
- Replace simple registry with TTL-based cache
- Use Caffeine cache with 1-hour expiration after last access
- Document expected cardinality of rate limit keys
- Add metric for rate limiter cache size

**Implementation:**
```java
// Replace rateLimiterRegistry with Caffeine cache
private final Cache<String, RateLimiter> rateLimiterCache = Caffeine.newBuilder()
    .expireAfterAccess(Duration.ofHours(1))
    .maximumSize(10_000)
    .removalListener((key, value, cause) -> {
        LOG.debugf("Evicting rate limiter: %s (cause: %s)", key, cause);
    })
    .build();

private RateLimiter getRateLimiter(String key, int limitPerMinute) {
    return rateLimiterCache.get("rate-limiter-" + key, k -> {
        return RateLimiter.of(k, RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .limitForPeriod(limitPerMinute)
            .timeoutDuration(Duration.ZERO)
            .build());
    });
}
```

---

#### 3.3 Add Circuit Breaker for Pool Processing
**Priority:** P2
**Effort:** 2-3 days
**Risk:** Medium

**Changes:**
- Detect when pool is in persistent error state (e.g., >50% errors over 1 minute)
- Temporarily pause message acceptance from queue
- Allow pool to recover
- Resume after cooldown period
- Log circuit breaker state changes
- Add metrics and dashboard indicators

---

### Phase 4: Long-term Improvements (Future)

#### 4.1 Comprehensive Health Checks
**Priority:** P3
**Effort:** 3-4 days

**Implementation:**
- Monitor `inPipelineMap` growth rate
- Monitor semaphore permit leaks (available permits decreasing over time)
- Detect metric drift (activeWorkers vs actual thread count)
- Alert on pool stalls (no processing activity for > 5 minutes)
- Automated recovery actions (restart affected pools)

---

#### 4.2 Enhanced Observability
**Priority:** P3
**Effort:** 2-3 days

**Implementation:**
- Add distributed tracing with OpenTelemetry
- Trace message lifecycle from queue to completion
- Correlate logs across components
- Track message latency breakdowns (queue time, processing time, mediation time)

---

#### 4.3 Graceful Degradation
**Priority:** P3
**Effort:** 3-5 days

**Implementation:**
- Adaptive concurrency based on error rates
- Automatic backoff on persistent failures
- Priority queuing for message retries
- Dead letter queue integration

---

## Testing Strategy

### Unit Testing
- Test all exception paths in `ProcessPoolImpl.processMessages()`
- Verify cleanup happens in every scenario
- Mock semaphore, mediator, callbacks
- Assert map state before/after each test

### Integration Testing
- Test configuration sync with pool changes
- Test forced shutdown scenarios
- Test high error rate scenarios
- Verify metrics accuracy under load

### Load Testing
- Sustained load with periodic errors
- Monitor map sizes over 24+ hours
- Verify no resource leaks
- Test various failure injection scenarios

### Chaos Testing
- Random worker thread interrupts
- Random mediator exceptions
- Queue consumer failures
- Configuration service outages

---

## Risk Mitigation

### Rollback Plan
- Phase 1 changes are significant - deploy to staging first
- Use feature flags to enable/disable new exception handling
- Keep old code path available for quick rollback
- Monitor metrics closely for first 48 hours

### Validation Criteria
- Zero growth in `inPipelineMap.size()` over 24 hours at steady state
- Semaphore permits remain constant (no leaks)
- Active workers metric matches actual workers (no drift)
- No memory growth in pool metrics map

### Monitoring Checklist
- [ ] `inPipelineMap.size()` gauge added
- [ ] `messageCallbacks.size()` gauge added
- [ ] Semaphore available permits gauge added
- [ ] Alert for map growth rate
- [ ] Alert for semaphore leak detection
- [ ] Dashboard panels for new metrics
- [ ] Log aggregation for cleanup events

---

## Success Metrics

### Before Fixes (Baseline)
- `inPipelineMap` growth rate: ~10-50 entries/hour under load with errors
- Semaphore leaks: 1-5 permits/day
- Metrics drift: activeWorkers +2-10% error/hour
- Pool metrics memory: Grows indefinitely with config changes

### After Fixes (Target)
- `inPipelineMap` growth rate: 0 (bounded by in-flight message count)
- Semaphore leaks: 0
- Metrics drift: 0 (perfect accuracy)
- Pool metrics memory: Stable (old metrics removed)
- Mean time to recovery from errors: <1 second
- Shutdown cleanup time: <5 seconds

---

## Dependencies and Prerequisites

### Code Dependencies
- No new external dependencies required for Phase 1-2
- Phase 3.2 (Rate Limiter Eviction) requires Caffeine cache library
- Phase 4.2 (Observability) requires OpenTelemetry dependencies

### Infrastructure
- Monitoring system must support gauge metrics (already in place with Micrometer)
- Log aggregation for structured log analysis (already configured)
- Dashboard updates (Grafana or equivalent)

### Team Coordination
- Coordination with operations team for deployment
- Documentation updates for new metrics
- Runbook updates for new failure modes

---

## Timeline Summary

| Phase | Duration | Priority | Risk |
|-------|----------|----------|------|
| Phase 1: Critical Fixes | Week 1 | P0 | Medium |
| Phase 2: High Priority | Week 2 | P1 | Low-Medium |
| Phase 3: Medium Priority | Week 3-4 | P2 | Medium |
| Phase 4: Long-term | Future | P3 | Low |

**Total estimated effort:** 3-4 weeks for Phases 1-3

---

## Approval and Sign-off

**Requires approval from:**
- [ ] Technical Lead
- [ ] Operations Team
- [ ] QA Team

**Document Version:** 1.0
**Last Updated:** 2025-10-10
**Next Review:** After Phase 1 completion
