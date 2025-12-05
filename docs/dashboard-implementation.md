# Dashboard Implementation Plan

## Overview
Implementing a real-time monitoring dashboard based on the Bun.js version, adapted for Java/Quarkus with Micrometer metrics.

## Architecture

### Metrics Services (Interface-based)

#### 1. QueueMetricsService
```java
public interface QueueMetricsService {
    void recordMessageReceived(String queueName);
    void recordMessageProcessed(String queueName, boolean success);
    void recordQueueDepth(String queueName, long depth);
    QueueStats getQueueStats(String queueName);
    Map<String, QueueStats> getAllQueueStats();
}
```

#### 2. PoolMetricsService
```java
public interface PoolMetricsService {
    void recordProcessingStart(String poolCode);
    void recordProcessingComplete(String poolCode, long durationMs, MediationResult result);
    void recordRateLimitExceeded(String poolCode, String rateLimitKey);
    PoolStats getPoolStats(String poolCode);
    Map<String, PoolStats> getAllPoolStats();
}
```

#### 3. WarningService
```java
public interface WarningService {
    void addWarning(String target, String type, String message);
    List<Warning> getActiveWarnings();
    void clearWarning(String target, String type);
}
```

#### 4. NotificationService (Placeholder)
```java
public interface NotificationService {
    CompletionStage<Void> sendNotification(Notification notification);
    void registerHandler(String type, NotificationHandler handler);
}
```

### Implementation with Micrometer

**MicrometerQueueMetricsService:**
- Uses Micrometer Counters for messages received/processed/failed
- Uses Micrometer Gauges for queue depth
- Uses Micrometer Timers for processing duration
- Maintains in-memory stats for dashboard aggregation

**MicrometerPoolMetricsService:**
- Counters for processing attempts by pool and result
- Timers for processing duration by pool
- Gauges for active workers, available permits
- Rate limit counters per pool

### Dashboard Endpoints

**GET /monitoring/health:**
```json
{
  "status": "HEALTHY|WARNING|DEGRADED",
  "timestamp": 1234567890,
  "uptime": 3600000
}
```

**GET /monitoring/queue-stats:**
```json
{
  "queue-high": {
    "name": "flow-catalyst-high-priority.fifo",
    "totalMessages": 1000,
    "totalConsumed": 950,
    "totalFailed": 50,
    "successRate": 0.95,
    "currentSize": 100,
    "throughput": 10.5
  }
}
```

**GET /monitoring/pool-stats:**
```json
{
  "POOL-HIGH": {
    "code": "POOL-HIGH",
    "concurrency": 10,
    "activeWorkers": 8,
    "availablePermits": 2,
    "totalProcessed": 5000,
    "successCount": 4800,
    "errorCount": 200,
    "avgDurationMs": 150.5
  }
}
```

**GET /monitoring/warnings:**
```json
[
  {
    "target": "flow-catalyst-high-priority.fifo",
    "type": "QUEUE_GROWTH_DETECTED",
    "message": "Queue has been growing for 5 minutes...",
    "timestamp": 1234567890
  }
]
```

**GET /monitoring/circuit-breakers:**
```json
{
  "http-mediator": {
    "name": "http-mediator",
    "state": "CLOSED|OPEN|HALF_OPEN",
    "failureRate": 0.15,
    "slowCallRate": 0.05,
    "bufferedCalls": 100,
    "failedCalls": 15
  }
}
```

### Dashboard HTML

Single-page dashboard at `/dashboard.html` with:
- Real-time metrics (5-second refresh)
- Charts.js for visualization
- Tailwind CSS for styling
- Same layout as Bun version

### Integration Points

**ProcessPoolImpl:**
```java
@Inject
PoolMetricsService poolMetrics;

private void processMessages() {
    poolMetrics.recordProcessingStart(poolCode);
    long startTime = System.currentTimeMillis();
    // ... processing ...
    poolMetrics.recordProcessingComplete(poolCode, duration, result);
}
```

**Queue Consumers:**
```java
@Inject
QueueMetricsService queueMetrics;

protected void consumeMessages() {
    // ... receive message ...
    queueMetrics.recordMessageReceived(queueIdentifier);
    // ... route ...
    queueMetrics.recordMessageProcessed(queueIdentifier, success);
}
```

## Files to Create

1. **Interfaces:**
   - `QueueMetricsService.java`
   - `PoolMetricsService.java`
   - `WarningService.java`
   - `NotificationService.java`

2. **Models:**
   - `QueueStats.java`
   - `PoolStats.java`
   - `Warning.java`
   - `Notification.java`
   - `HealthStatus.java`
   - `CircuitBreakerStats.java`

3. **Implementations:**
   - `MicrometerQueueMetricsService.java`
   - `MicrometerPoolMetricsService.java`
   - `InMemoryWarningService.java`
   - `NoOpNotificationService.java` (placeholder)

4. **Endpoints:**
   - `DashboardResource.java` - Serves dashboard.html
   - `MonitoringResource.java` - REST API for metrics

5. **Frontend:**
   - `dashboard.html` - Main dashboard page

## Micrometer Configuration

```properties
# Enable Prometheus endpoint
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.binder.jvm=true
quarkus.micrometer.binder.system=true

# Custom metrics
quarkus.micrometer.binder.http-client.enabled=true
```

## Next Steps

1. Create all interface files
2. Create model/DTO classes
3. Implement Micrometer-based services
4. Create REST endpoints
5. Port dashboard HTML from Bun version
6. Integrate metrics into existing components
7. Add queue growth monitoring (future)
8. Add notification system (future - webhook, email, Slack, etc.)
