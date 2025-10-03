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

**Lifecycle:**
1. **Startup:** Delayed initialization (2s after HTTP server ready) → Fetch config → Start pools → Start consumers
2. **Sync:** Stop consumers → Drain/stop changed pools → Start new pools → Start new consumers (every 5m)
3. **Shutdown:** Stop consumers (graceful) → Drain all pools

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
- CLIENT_ACKNOWLEDGE mode
- Ack: `message.acknowledge()`
- Nack: `session.recover()` (triggers redelivery)

### 3. Process Pools

**Class:** `ProcessPoolImpl`
**Interface:** `ProcessPool`

**Responsibilities:**
- Manage BlockingQueue of messages (default capacity: 1000)
- Control concurrency via Semaphore
- Apply rate limiting per rate limit key
- Process messages through Mediator
- Invoke ack/nack callbacks

**Architecture:**
- One pool per pool code (e.g., "POOL-A", "POOL-B")
- BlockingQueue controls backpressure
- N worker threads (= concurrency setting)
- Each worker: poll queue → acquire semaphore → check rate limit → mediate → ack/nack → release semaphore

**Rate Limiting:**
- Uses Resilience4j RateLimiter
- Per `rateLimitKey` from message
- Sliding window, 1-minute period
- Checked after acquiring semaphore

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
Acquire Semaphore (concurrency control)
  ↓
Check Rate Limit (optional)
  ↓
Mediator.process() → HTTP call to downstream
  ↓
QueueManager.ack/nack → Consumer callback
  ↓
Release Semaphore
```

## Message Schema

**MessagePointer:**
```json
{
  "id": "msg-12345",
  "poolCode": "POOL-A",
  "rateLimitPerMinute": 100,
  "rateLimitKey": "customer-123",
  "authToken": "Bearer xyz...",
  "mediationType": "HTTP",
  "mediationTarget": "https://api.example.com/process"
}
```

## Configuration

### Message Router Config (from control endpoint)

**Endpoint:** `/api/message-router/queue-config`

**Response:**
```json
{
  "queues": [
    {"queueName": "queue-1", "queueUri": null},
    {"queueName": "queue-2", "queueUri": null}
  ],
  "connections": 1,
  "processingPools": [
    {"code": "POOL-A", "concurrency": 5},
    {"code": "POOL-B", "concurrency": 10}
  ]
}
```

### Application Properties

```properties
# Message Router
message-router.queue-type=SQS
message-router.sync-interval=5m
message-router.queue-capacity=1000

# SQS Configuration
quarkus.sqs.aws.region=us-east-1

# ActiveMQ Configuration
activemq.broker.url=tcp://localhost:61616
activemq.username=admin
activemq.password=admin

# Logging (Dev)
%dev.quarkus.log.console.json=false
%dev.quarkus.log.console.format=%d{HH:mm:ss} %-5p [%c{2.}] (%t) %s%e%n
%dev.quarkus.log.level=INFO
%dev.quarkus.log.category."tech.flowcatalyst".level=DEBUG

# Logging (Prod - Structured JSON)
%prod.quarkus.log.console.json=true
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

### Structured Logging

Uses Quarkus logging with MDC for structured JSON logs in production.

**MDC Fields:**
- `messageId` - Unique message identifier
- `poolCode` - Processing pool code
- `queueName` / `queueUrl` - Source queue
- `targetUri` - Destination endpoint
- `mediationType` - Type of mediator
- `rateLimitKey` - Rate limit key (if applicable)
- `rateLimitPerMinute` - Rate limit value (if applicable)
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
- Resilience4j RateLimiter per `rateLimitKey`
- Sliding window, 1-minute period
- Messages nacked when limit exceeded

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
- BlockingQueue in ProcessPool (default 1000)
- Messages not routed if queue full
- Rely on queue visibility timeout for redelivery

## Deduplication

Global `ConcurrentHashMap<String, MessagePointer>` in QueueManager tracks messages in pipeline.

**Flow:**
1. Consumer receives message
2. QueueManager checks if `messageId` exists in map
3. If exists: return false, consumer discards
4. If not exists: add to map, route to pool
5. ProcessPool removes from map after processing

**Why?**
- FIFO queues can deliver same message multiple times during visibility timeout
- Prevents duplicate processing within the system
- Allows safe message redelivery from queue

## Configuration Sync

**Frequency:** Every 5 minutes (configurable via `message-router.sync-interval`)

**Process:**
1. Stop all queue consumers (prevent new messages)
2. Compare current pools with new config
3. Drain and stop pools with changed concurrency
4. Start new pools
5. Start queue consumers with new configuration

**Concurrency Changes:**
- Stop pool completely
- Wait for drain (queue empty + all semaphore permits returned)
- Create new pool with new concurrency
- More stable than dynamic semaphore adjustment

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
- ✅ Dynamic configuration sync (every 5 minutes)
- ✅ Concurrent processing pools with configurable concurrency
- ✅ Per-key rate limiting
- ✅ Circuit breaker and retry patterns
- ✅ Graceful shutdown handling
- ✅ Message deduplication
- ✅ Real-time monitoring dashboard
- ✅ Queue and pool metrics
- ✅ Warning system
- ✅ Structured logging (JSON in prod, readable in dev)

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
