# Flow Catalyst Architecture

## Overview

Flow Catalyst is a high-performance message routing and processing system built with Quarkus and Java 21 virtual threads. It consumes messages from queues (SQS or ActiveMQ), routes them to processing pools, and mediates them to downstream services with concurrency control and rate limiting.

## Core Components

### 1. Queue Manager

**Class:** `QueueManager`
**Role:** Central orchestrator that manages queue consumers and processing pools

**Responsibilities:**
- Fetches configuration from control endpoint on startup
- Creates and manages ProcessPools
- Creates and manages QueueConsumers
- Routes messages from consumers to appropriate pools
- Handles configuration sync every 5 minutes (configurable)
- Manages message deduplication via global in-pipeline map
- Coordinates ack/nack callbacks between pools and consumers

**Pool Management:**
- Maximum pools: 2000 (configurable via `message-router.max-pools`)
- Warning threshold: 1000 pools (configurable via `message-router.pool-warning-threshold`)
- Real-time monitoring via `flowcatalyst.queuemanager.pools.active` metric
- Automated leak detection every 30 seconds

**Lifecycle:**
1. **Startup:** Delayed initialization (2s after HTTP server ready) → Fetch config → Start pools → Start consumers
2. **Sync:** Incremental update (NO stop-the-world):
   - Only stop/drain pools that changed (concurrency or rate limit modified)
   - Only stop consumers for removed queues
   - Keep unchanged resources running for high availability
3. **Shutdown:** Stop consumers (graceful) → Drain all pools → Cleanup remaining messages

### 2. Queue Consumers

**Base:** `AbstractQueueConsumer`
**Implementations:** `SqsQueueConsumer`, `ActiveMqQueueConsumer`

**Responsibilities:**
- Poll/consume messages from queues
- Parse message bodies to `MessagePointer`
- Set MDC context for structured logging
- Route messages to QueueManager with callback
- Handle queue-specific ack/nack operations

**Configuration:**
- 1 consumer per queue
- N connections per consumer (configurable, default 1)
- Each connection runs on a virtual thread

**SQS Specific:**
- Long polling (20 seconds)
- Max 10 messages per poll
- Ack: Delete message from queue
- Nack: No-op (visibility timeout handles redelivery)
- Graceful shutdown: Completes current poll, processes batch, then exits

**ActiveMQ Specific:**
- INDIVIDUAL_ACKNOWLEDGE mode (prevents head-of-line blocking)
- Ack: `message.acknowledge()` (acknowledges only this message)
- Nack: No-op (like SQS - message redelivered via RedeliveryPolicy)
- RedeliveryPolicy: 30-second delay, no exponential backoff
- Shared connection with per-thread sessions

### 3. Process Pools

**Class:** `ProcessPoolImpl`
**Interface:** `ProcessPool`

**Responsibilities:**
- Manage BlockingQueue of messages (capacity: max(concurrency × 10, 500))
- Control concurrency via Semaphore
- Apply rate limiting per rate limit key
- Process messages through Mediator
- Invoke ack/nack callbacks

**Architecture:**
- One pool per pool code (e.g., "POOL-A", "POOL-B")
- BlockingQueue controls backpressure (scales with concurrency)
- N worker threads (= concurrency setting, uses virtual threads)
- Each worker: poll queue → **check rate limit** → acquire semaphore → mediate → ack/nack → release semaphore
- **Rate limit checked BEFORE semaphore** to prevent wasting concurrency slots

**Buffer Sizing:**
- Queue capacity = max(concurrency × 10, 500)
- Examples: 5 workers → 500 buffer, 100 workers → 1000 buffer, 200 workers → 2000 buffer
- Scales processing buffer with processing capacity
- Rejected messages stay in SQS/ActiveMQ with visibility timeout

**Rate Limiting:**
- **Pool-level rate limiting** (not per-message)
- Optional `rateLimitPerMinute` configured per pool
- Uses Resilience4j RateLimiter (one per pool)
- Sliding window, 1-minute period
- **Checked BEFORE acquiring semaphore** (optimization)
- Messages nacked when rate limited (don't block workers)
- Automatic cleanup when pool removed during config sync

### 4. Mediators

**Interface:** `Mediator`
**Implementation:** `HttpMediator`

**Responsibilities:**
- Send message to downstream service
- Return `MediationResult` (SUCCESS, ERROR_CONNECTION, ERROR_SERVER, ERROR_PROCESS)

**HTTP Mediator:**
- Java 11 HTTP/2 client with virtual thread executor
- SmallRye Fault Tolerance: @Retry, @CircuitBreaker, @Timeout
- Retry on connection/timeout errors (max 3 attempts)
- Circuit breaker: 50% failure ratio, 10 request threshold
- Maps HTTP status codes to MediationResult

### 5. Message Flow

```
Queue (SQS/ActiveMQ)
  ↓
QueueConsumer (N connections per queue)
  ↓ parse & set MDC
QueueManager.routeMessage()
  ↓ dedup check & routing
ProcessPool.submit() → BlockingQueue
  ↓ poll from queue
Check Rate Limit (pool-level, BEFORE semaphore)
  ↓ if rate-limited: nack & continue (no semaphore)
Acquire Semaphore (concurrency control)
  ↓ record processing started
Mediator.process() → HTTP call to downstream
  ↓ handle result
QueueManager.ack/nack → Consumer callback
  ↓ cleanup (ALWAYS in finally block)
Release Semaphore & remove from pipeline map
```

## Message Schema

**MessagePointer:**
```json
{
  "id": "msg-12345",
  "poolCode": "POOL-A",
  "authToken": "Bearer xyz...",
  "mediationType": "HTTP",
  "mediationTarget": "https://api.example.com/process"
}
```

**Note:** Rate limiting is now configured at the pool level, not per-message.

## Configuration

### Message Router Config (from control endpoint)

**Endpoint:** `/api/config`

**Response:**
```json
{
  "queues": [
    {"queueName": "queue-1", "queueUri": null},
    {"queueName": "queue-2", "queueUri": null}
  ],
  "connections": 1,
  "processingPools": [
    {"code": "POOL-A", "concurrency": 5, "rateLimitPerMinute": null},
    {"code": "POOL-B", "concurrency": 10, "rateLimitPerMinute": 600}
  ]
}
```

**Pool Configuration:**
- `code`: Unique pool identifier
- `concurrency`: Number of concurrent workers
- `rateLimitPerMinute`: Optional pool-level rate limit (null = no limit)

### Application Properties

```properties
# Message Router
message-router.queue-type=SQS
message-router.sync-interval=5m
message-router.max-pools=2000
message-router.pool-warning-threshold=1000

# SQS Configuration
quarkus.sqs.aws.region=us-east-1

# ActiveMQ Configuration
activemq.broker.url=tcp://localhost:61616
activemq.username=admin
activemq.password=admin

# Logging (Dev)
%dev.quarkus.log.console.format=%d{HH:mm:ss} %-5p [%c{2.}] (%t) %s%e%n
%dev.quarkus.log.level=INFO
%dev.quarkus.log.category."tech.flowcatalyst".level=DEBUG

# Logging (Prod - Structured JSON)
%prod.quarkus.log.console.format=json
%prod.quarkus.log.console.json.pretty-print=false
%prod.quarkus.log.level=INFO
%prod.quarkus.log.console.json.additional-field."service.name".value=flow-catalyst
%prod.quarkus.log.console.json.additional-field."service.version".value=${quarkus.application.version:1.0.0-SNAPSHOT}
%prod.quarkus.log.console.json.additional-field."environment".value=${ENVIRONMENT:production}
```

## Observability

### Monitoring Dashboard

Real-time monitoring dashboard available at `/dashboard.html` with:
- Queue metrics (messages received, processed, failed, throughput)
- Pool metrics (concurrency, active workers, success/error rates, avg duration)
- System warnings and alerts
- Circuit breaker status
- Health status

**Dashboard Endpoints:**
- `GET /monitoring/health` - Overall system health
- `GET /monitoring/queue-stats` - Queue statistics
- `GET /monitoring/pool-stats` - Processing pool statistics
- `GET /monitoring/warnings` - Active warnings
- `GET /monitoring/circuit-breakers` - Circuit breaker states

### Kubernetes Health Probes

Flow Catalyst provides Kubernetes-style health endpoints following standard probe patterns for container orchestration:

**Liveness Probe** (`GET /health/live`):
- Checks if the application is running and not deadlocked
- Returns 200 if alive, 503 if dead
- Fast response (<100ms)
- Does NOT check external dependencies
- Failure triggers container restart

**Readiness Probe** (`GET /health/ready`):
- Checks if application is ready to serve traffic
- Returns 200 if ready, 503 if not ready
- Checks performed:
  - QueueManager is initialized
  - Message broker (SQS/ActiveMQ) is accessible
  - Processing pools are operational (not all stalled)
- Failure removes pod from load balancer rotation
- Can be slower (timeout typically 1-5 seconds)

**Startup Probe** (`GET /health/startup`):
- Similar to readiness but with more lenient timeout/failure thresholds
- Use for slow-starting applications
- Kubernetes waits for startup before running liveness/readiness probes

**Kubernetes Configuration Example:**
```yaml
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: flowcatalyst
    livenessProbe:
      httpGet:
        path: /health/live
        port: 8080
      initialDelaySeconds: 30
      periodSeconds: 10
      timeoutSeconds: 3
      failureThreshold: 3

    readinessProbe:
      httpGet:
        path: /health/ready
        port: 8080
      initialDelaySeconds: 10
      periodSeconds: 5
      timeoutSeconds: 5
      failureThreshold: 3
```

### Broker Health Checks

**BrokerHealthService** provides explicit connectivity checks for message brokers:

**SQS Connectivity:**
- Validates AWS credentials and network connectivity
- Checks queue accessibility with `GetQueueUrl` API
- Fast-fail on misconfiguration

**ActiveMQ Connectivity:**
- Creates test connection to verify broker availability
- Validates credentials and network access
- Properly closes test connections

**Broker Metrics:**
- `flowcatalyst.broker.connection.attempts` - Total connection attempts
- `flowcatalyst.broker.connection.successes` - Successful connections
- `flowcatalyst.broker.connection.failures` - Failed connections
- `flowcatalyst.broker.available` (gauge) - 1 if connected, 0 if not

**Benefits:**
- Fail fast on startup if broker is unreachable
- Clear separation of application vs infrastructure issues
- Kubernetes readiness probe integration
- Prometheus alerting on broker connectivity

### Structured Logging

Uses Quarkus logging with MDC for structured JSON logs in production.

**MDC Fields:**
- `messageId` - Unique message identifier
- `poolCode` - Processing pool code
- `queueName` / `queueUrl` - Source queue
- `targetUri` - Destination endpoint
- `mediationType` - Type of mediator
- `result` - Mediation result (SUCCESS/ERROR_*)
- `durationMs` - Processing duration

**Example Queries:**
```
# Find all failed messages for a pool
level:ERROR AND poolCode:POOL-A

# Find slow processing
durationMs:>5000

# Track a specific message
messageId:msg-12345
```

## Virtual Threads

All I/O operations run on Java 21 virtual threads:
- Queue consumer connections
- ProcessPool workers
- HTTP client executor

Benefits:
- Lightweight concurrency (thousands of threads)
- Blocking I/O without platform thread overhead
- Simplified programming model (no reactive complexity)

## Fault Tolerance

### Rate Limiting
- **Pool-level** rate limiting (optional per pool)
- Resilience4j RateLimiter (one per pool with rate limit configured)
- Sliding window, 1-minute period
- Messages nacked when limit exceeded (no semaphore acquired)
- Automatic cleanup when pool removed

### Circuit Breaker
- SmallRye implementation on HTTP Mediator
- 50% failure ratio threshold
- Opens after 10 requests
- 5-second delay before half-open
- 3 successful calls to close

### Retry
- Max 3 retries on connection/timeout errors
- 1 second delay with 500ms jitter
- Only retries transient errors

### Backpressure
- BlockingQueue in ProcessPool (capacity: max(concurrency × 10, 500))
- Buffer size scales with processing capacity
- Messages not routed if queue full
- Rejected messages rely on queue visibility timeout for redelivery
- SQS/ActiveMQ acts as overflow buffer when pools are overwhelmed

## Deduplication & Resource Cleanup

Global `ConcurrentHashMap<String, MessagePointer>` in QueueManager tracks messages in pipeline.

**Flow:**
1. Consumer receives message
2. QueueManager checks if `messageId` exists in map
3. If exists: return false, consumer discards
4. If not exists: add to map, route to pool
5. ProcessPool removes from map **in finally block** (guaranteed cleanup)

**Why?**
- FIFO queues can deliver same message multiple times during visibility timeout
- Prevents duplicate processing within the system
- Allows safe message redelivery from queue

**Resource Cleanup Guarantees:**
- **Always in finally block** - Never leaks resources
- **Semaphore permits** - Always released (even on exceptions)
- **Pipeline map** - Always cleaned (prevents memory leaks)
- **Metrics** - Always balanced (started/finished paired)
- **Automated leak detection** - Runs every 30 seconds
- **Map size monitoring** - `flowcatalyst.queuemanager.pipeline.size` metric

## Configuration Sync

**Frequency:** Every 5 minutes (configurable via `message-router.sync-interval`)

**Incremental Sync Process (High Availability):**
1. **Fetch new configuration** from control endpoint
2. **Compare pools:**
   - Unchanged pools (same concurrency & rate limit): Keep running ✅
   - Changed pools (concurrency or rate limit changed): Drain and recreate ⚠️
   - Removed pools: Drain, remove, and cleanup metrics ❌
   - New pools: Create and start ➕
3. **Compare queues:**
   - Unchanged queues: Keep consumers running ✅
   - Removed queues: Stop consumers ❌
   - New queues: Start consumers ➕

**Benefits:**
- **Zero interruption** for unchanged resources
- **High availability** maintained during sync
- **Surgical updates** only affect changed components
- **Fast sync** with minimal downtime

**Pool Changes:**
- Concurrency or rate limit change triggers pool recreation
- Old pool drains gracefully (queue empty + all permits returned)
- New pool created with updated configuration
- Metrics cleaned up for removed pools

## Dependencies

**Key Libraries:**
- Quarkus 3.28.2
- Java 21 (virtual threads)
- AWS SDK for SQS
- ActiveMQ Client 6.1.7
- Resilience4j (rate limiting)
- SmallRye Fault Tolerance (circuit breaker, retry)
- Micrometer (metrics)
- Jackson (JSON parsing)

## Implemented Features

- ✅ Multi-queue support (SQS and ActiveMQ)
- ✅ Incremental configuration sync (high availability, no stop-the-world)
- ✅ Concurrent processing pools with configurable concurrency
- ✅ Pool-level rate limiting (optional per pool)
- ✅ Configurable pool limits (max 2000, warning at 1000)
- ✅ Circuit breaker and retry patterns
- ✅ Graceful shutdown with message cleanup
- ✅ Message deduplication with guaranteed resource cleanup
- ✅ Automated leak detection (maps, semaphores, metrics)
- ✅ Real-time monitoring dashboard
- ✅ Comprehensive metrics (queue, pool, infrastructure)
- ✅ Warning system with automated alerts
- ✅ Structured logging (JSON in prod, readable in dev)
- ✅ ActiveMQ RedeliveryPolicy (30s delay, no exponential backoff)
- ✅ Individual message acknowledgment (no head-of-line blocking)

## Dispatch Jobs System

Flow Catalyst includes a **Dispatch Jobs** system for reliable webhook delivery built on top of the message router.

**Key Features:**
- Signed webhooks with HMAC SHA-256 + bearer tokens
- Configurable retry logic with full attempt tracking
- Searchable metadata using indexed key-value pairs
- Database-agnostic design (PostgreSQL + future MongoDB support)
- Integration with message router for processing

**See:**
- [Dispatch Jobs Documentation](dispatch-jobs.md) for complete details
- [Database Strategy](database-strategy.md) for database design decisions

## Future Enhancements

### Message Router
- Multiple mediator types (HTTP + Dispatch Job implemented)
- OpenTelemetry integration for distributed tracing
- Metrics export (Prometheus)
- Dead letter queue handling
- Notification service (email, Slack, webhooks)
- Message group support for better observability

### Dispatch Jobs
- Credential encryption at rest
- Additional protocols (gRPC, SNS, S3)
- Batch job creation API
- Job cancellation API
