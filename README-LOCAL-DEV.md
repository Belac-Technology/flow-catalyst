# Local Development Setup

## Prerequisites

- Docker and Docker Compose
- Java 21
- Gradle

## Starting ElasticMQ (Local SQS)

Start ElasticMQ manually on port 9324. The application expects three FIFO queues:
- `flow-catalyst-high-priority.fifo`
- `flow-catalyst-medium-priority.fifo`
- `flow-catalyst-low-priority.fifo`

An `elasticmq.conf` and `docker-compose.yml` are provided for reference if you want to use Docker later.

## Running the Application

```bash
./gradlew quarkusDev
```

## Test Endpoints

### Response Simulation Endpoints

- **Fast (100ms):** `POST http://localhost:8080/api/test/fast`
- **Slow (60s):** `POST http://localhost:8080/api/test/slow`
- **Faulty (random):** `POST http://localhost:8080/api/test/faulty`
  - 60% success (200)
  - 20% client error (400)
  - 20% server error (500)
- **Always Fail:** `POST http://localhost:8080/api/test/fail`

### Seeding Messages

Send test messages to queues using a JSON body:

```bash
# Seed 100 messages randomly across queues
curl -X POST http://localhost:8080/api/seed/messages \
  -H "Content-Type: application/json" \
  -d '{"count": 100, "queue": "random", "endpoint": "random"}'

# Seed to specific queue with specific endpoint
curl -X POST http://localhost:8080/api/seed/messages \
  -H "Content-Type: application/json" \
  -d '{"count": 50, "queue": "high", "endpoint": "fast"}'

# Defaults apply if no body is provided
curl -X POST http://localhost:8080/api/seed/messages \
  -H "Content-Type: application/json" \
  -d '{}'
```

**Request Body (JSON):**
```json
{
  "count": 10,       // Number of messages (default: 10)
  "queue": "random", // Target queue: "high", "medium", "low", "random", or full queue name (default: "random")
  "endpoint": "random" // Target endpoint: "fast", "slow", "faulty", "fail", "random", or full URL (default: "random")
}
```

**Response:**
```json
{
  "status": "success",
  "messagesSent": 50,
  "totalRequested": 50
}
```

### Configuration Endpoint

The local dev config endpoint is available at:
```
GET http://localhost:8080/api/config
```

## Queue Configuration

The dev profile uses 3 queues with 3 processing pools:

**Queues:**
- `flow-catalyst-high-priority.fifo` → POOL-HIGH (concurrency: 10)
- `flow-catalyst-medium-priority.fifo` → POOL-MEDIUM (concurrency: 5)
- `flow-catalyst-low-priority.fifo` → POOL-LOW (concurrency: 2)

**Processing Pools:**
- POOL-HIGH: 10 concurrent workers
- POOL-MEDIUM: 5 concurrent workers
- POOL-LOW: 2 concurrent workers

## Monitoring

### Logs

Structured JSON logs are disabled in dev mode for readability. You'll see:
- Message routing with MDC context (messageId, poolCode, queueName)
- Processing duration
- Success/failure results

### OpenAPI/Swagger

Access the API docs at: http://localhost:8080/q/swagger-ui

### Dev UI

Quarkus Dev UI: http://localhost:8080/q/dev
