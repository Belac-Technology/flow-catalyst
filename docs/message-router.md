# Message Router Module

> **Note:** For detailed architecture documentation, see [architecture.md](architecture.md)

## Overview
The message router is responsible for polling messages from configured queues and routing them to appropriate processing pools for concurrent processing.

## Configuration
On startup, the application fetches queue configuration from a control endpoint: `/api/config`

### Configuration Structure

**MessageRouterConfig Response:**
```json
{
  "queues": [...],
  "connections": 1,
  "processingPools": [...]
}
```

#### Queues
List of queue configurations containing:
- `queueName` - Queue name (for ActiveMQ)
- `queueUri` - Queue URI (for SQS)

#### Connections
Number of connections/pollers per queue:
- `1` = 1 SQS poller or 1 ActiveMQ connection
- Can be increased for higher throughput

#### Processing Pools
List of processing pool configurations:
- `code` - Unique identifier for the pool
- `concurrency` - Number of messages that can be processed concurrently

**Example:**
```json
{
  "queues": [
    {"queueName": "test-queue-1", "queueUri": null},
    {"queueName": "test-queue-2", "queueUri": null}
  ],
  "connections": 1,
  "processingPools": [
    {"code": "POOL-A", "concurrency": 5},
    {"code": "POOL-B", "concurrency": 10}
  ]
}
```

## Queue Types
The queue type is configured in `application.properties` via `message-router.queue-type`.

Supported types:
- **SQS** - Amazon Simple Queue Service
- **ACTIVEMQ** - Apache ActiveMQ Classic

## Architecture

### Components

#### Domain Models (`tech.flowcatalyst.messagerouter.config`)
- `QueueType` - Enum defining supported queue types
- `QueueConfig` - Queue configuration (name or URI)
- `ProcessingPool` - Processing pool configuration
- `MessageRouterConfig` - Main configuration object

#### REST Client (`tech.flowcatalyst.messagerouter.client`)
- `MessageRouterConfigClient` - REST client for fetching configuration from control endpoint

#### Local Development Endpoint (`tech.flowcatalyst.messagerouter.endpoint`)
- `LocalConfigResource` - Dev-profile-only endpoint serving default configuration

## Configuration Properties

### application.properties

```properties
# Message Router Configuration
message-router.queue-type=SQS

# REST Client Configuration
# Full URL including path, e.g., http://localhost:8000/api/config
quarkus.rest-client.message-router-config.url=${MESSAGE_ROUTER_CONFIG_URL:http://localhost:8080/api/config}
quarkus.rest-client.message-router-config.scope=jakarta.inject.Singleton
```

### Environment Variables
- `MESSAGE_ROUTER_CONFIG_URL` - Full URL to config endpoint including path (defaults to `http://localhost:8080/api/config`)

## Local Development
For local development and testing, a local configuration endpoint is available when running in dev profile (`quarkus:dev`). This endpoint serves default configuration without requiring an external control endpoint.

The local endpoint returns:
- 2 test queues
- 1 connection
- 2 processing pools (POOL-A with concurrency 5, POOL-B with concurrency 10)

## Virtual Threads
The application uses Java 21 virtual threads for lightweight concurrency, allowing efficient handling of many concurrent message processing tasks.
