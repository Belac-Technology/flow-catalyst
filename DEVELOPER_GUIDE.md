# Developer Guide - FlowCatalyst

Complete guide for developers working with FlowCatalyst Message Router and Core applications.

## Table of Contents

- [Quick Reference](#quick-reference)
- [Development Mode](#development-mode)
- [Building Applications](#building-applications)
- [Running Built Applications](#running-built-applications)
- [Queue Configuration](#queue-configuration)
- [Environment Variables](#environment-variables)
- [Profiles Explained](#profiles-explained)
- [Chronicle Queue (Internal Broker)](#chronicle-queue-internal-broker)
- [Troubleshooting](#troubleshooting)

---

## Quick Reference

```bash
# Development mode (hot reload)
./gradlew :flowcatalyst-message-router:quarkusDev  # Message Router only
./gradlew :flowcatalyst-core:quarkusDev             # Core only
./gradlew :flowcatalyst-app:quarkusDev              # Full-stack

# Build JARs
./gradlew :flowcatalyst-message-router:build        # Message Router
./gradlew :flowcatalyst-app:build                   # Full-stack

# Build native images
./gradlew nativeBuild                                # Production (SQS/ActiveMQ)
./gradlew nativeBuildDev                             # Developer (Chronicle Queue)

# Run built JARs
java -jar flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar
java -jar flowcatalyst-app/build/quarkus-app/quarkus-run.jar
```

---

## Development Mode

### Scenario 1: Message Router with LocalStack SQS

Run message router in dev mode with LocalStack SQS and point to a config endpoint.

```bash
# Prerequisites: LocalStack running on port 4566

# Start with default config endpoint (http://localhost:8080/api/config)
./gradlew :flowcatalyst-message-router:quarkusDev

# OR specify custom config endpoint
MESSAGE_ROUTER_CONFIG_URL=http://localhost:8000/api/config \
  ./gradlew :flowcatalyst-message-router:quarkusDev
```

**What this does:**
- Uses LocalStack SQS at `localhost:4566` (configured in dev profile)
- Connects to config endpoint for queue/pool configuration
- Enables hot reload
- Human-readable logs (not JSON)
- Debug logging for FlowCatalyst code

### Scenario 2: Message Router with Chronicle Queue (No External Broker)

Run with embedded Chronicle Queue - no SQS or ActiveMQ needed!

```bash
# Use chronicle-dev profile
./gradlew :flowcatalyst-message-router:quarkusDev -Dquarkus.profile=chronicle-dev

# OR with custom config endpoint
MESSAGE_ROUTER_CONFIG_URL=http://localhost:8000/api/config \
  ./gradlew :flowcatalyst-message-router:quarkusDev -Dquarkus.profile=chronicle-dev
```

**What this does:**
- Uses embedded Chronicle Queue (file-based)
- No external queue broker required
- Queue files stored in `./dev-chronicle-queues/`
- REST API available at `http://localhost:8080/api/chronicle/*`

### Scenario 3: Core App Development

Run the core application (dispatch jobs, webhooks, database).

```bash
# Prerequisites: PostgreSQL running on port 5432

./gradlew :flowcatalyst-core:quarkusDev
```

**What this does:**
- Starts dispatch job system
- Requires PostgreSQL database
- Includes router dependencies
- Hot reload enabled

### Scenario 4: Full-Stack Development

Run both message router and core together.

```bash
# Prerequisites: PostgreSQL + (LocalStack SQS OR ActiveMQ)

./gradlew :flowcatalyst-app:quarkusDev
```

---

## Building Applications

### Build Message Router

```bash
# JVM build (fast, for deployment)
./gradlew :flowcatalyst-message-router:build

# Output location:
# flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar
```

### Build Core App

```bash
# JVM build
./gradlew :flowcatalyst-core:build

# Output location:
# flowcatalyst-core/build/quarkus-app/quarkus-run.jar
```

### Build Full-Stack App

```bash
# JVM build
./gradlew :flowcatalyst-app:build

# Output location:
# flowcatalyst-app/build/quarkus-app/quarkus-run.jar
```

### Build Native Images

```bash
# Production native image (SQS/ActiveMQ - no Chronicle)
cd flowcatalyst-message-router
../gradlew nativeBuild

# Developer native image (with Chronicle Queue)
cd flowcatalyst-message-router
../gradlew nativeBuildDev

# Output location:
# flowcatalyst-message-router/build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner[.exe]
```

---

## Running Built Applications

### Run Message Router JAR

The message router can use **SQS**, **ActiveMQ**, or **Chronicle Queue** depending on configuration.

#### Option 1: With SQS (AWS or LocalStack)

```bash
# With AWS SQS (production)
AWS_REGION=us-east-1 \
MESSAGE_ROUTER_CONFIG_URL=http://config-service:8080/api/config \
  java -jar flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar

# With LocalStack SQS (development)
SQS_ENDPOINT_OVERRIDE=http://localhost:4566 \
AWS_REGION=us-east-1 \
AWS_ACCESS_KEY_ID=test \
AWS_SECRET_ACCESS_KEY=test \
MESSAGE_ROUTER_CONFIG_URL=http://localhost:8000/api/config \
  java -jar flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar
```

#### Option 2: With ActiveMQ

```bash
ACTIVEMQ_BROKER_URL=tcp://localhost:61616 \
ACTIVEMQ_USERNAME=admin \
ACTIVEMQ_PASSWORD=admin \
MESSAGE_ROUTER_CONFIG_URL=http://config-service:8080/api/config \
  java -Dmessage-router.queue-type=ACTIVEMQ \
  -jar flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar
```

#### Option 3: With Chronicle Queue (Internal Broker)

```bash
# Use chronicle-dev profile
java -Dquarkus.profile=chronicle-dev \
  -jar flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar

# OR set environment variables
MESSAGE_ROUTER_QUEUE_TYPE=CHRONICLE \
CHRONICLE_QUEUE_ENABLED=true \
CHRONICLE_QUEUE_BASE_PATH=./chronicle-queues \
  java -jar flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar
```

### Run Chronicle Native Binary

If you built the native image with Chronicle Queue:

```bash
# Linux/macOS
./flowcatalyst-message-router/build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner

# Windows
.\flowcatalyst-message-router\build\flowcatalyst-message-router-1.0.0-SNAPSHOT-runner.exe

# With custom config endpoint
MESSAGE_ROUTER_CONFIG_URL=http://localhost:8000/api/config \
  ./flowcatalyst-message-router/build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner
```

**What happens:**
- Chronicle Queue starts automatically
- No external broker needed
- Queue files stored in `./chronicle-queues/` (or custom path)
- REST API available for queue management

### Run Core App JAR

```bash
# Prerequisites: PostgreSQL running

# Basic run
java -jar flowcatalyst-core/build/quarkus-app/quarkus-run.jar

# With custom database
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://db-server:5432/flowcatalyst \
QUARKUS_DATASOURCE_USERNAME=myuser \
QUARKUS_DATASOURCE_PASSWORD=mypassword \
  java -jar flowcatalyst-core/build/quarkus-app/quarkus-run.jar
```

### Run Full-Stack App JAR

```bash
# Prerequisites: PostgreSQL + (SQS OR ActiveMQ OR Chronicle)

# With SQS
AWS_REGION=us-east-1 \
MESSAGE_ROUTER_CONFIG_URL=http://config:8080/api/config \
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://db:5432/flowcatalyst \
  java -jar flowcatalyst-app/build/quarkus-app/quarkus-run.jar

# With ActiveMQ
ACTIVEMQ_BROKER_URL=tcp://activemq:61616 \
MESSAGE_ROUTER_CONFIG_URL=http://config:8080/api/config \
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://db:5432/flowcatalyst \
  java -Dmessage-router.queue-type=ACTIVEMQ \
  -jar flowcatalyst-app/build/quarkus-app/quarkus-run.jar
```

---

## Queue Configuration

FlowCatalyst supports three queue types: **SQS**, **ActiveMQ**, and **Chronicle Queue**.

### Switching Queue Types

You can switch queue types using:
1. **Profiles** (predefined configurations)
2. **Environment variables**
3. **System properties** (`-D` flags)

#### Method 1: Using Profiles

```bash
# Default profile = SQS with LocalStack (dev mode)
./gradlew quarkusDev

# Chronicle Queue profile
./gradlew quarkusDev -Dquarkus.profile=chronicle-dev
```

#### Method 2: Environment Variables

```bash
# Use SQS (default)
MESSAGE_ROUTER_QUEUE_TYPE=SQS java -jar app.jar

# Use ActiveMQ
MESSAGE_ROUTER_QUEUE_TYPE=ACTIVEMQ \
ACTIVEMQ_BROKER_URL=tcp://localhost:61616 \
  java -jar app.jar

# Use Chronicle Queue
MESSAGE_ROUTER_QUEUE_TYPE=CHRONICLE \
CHRONICLE_QUEUE_ENABLED=true \
CHRONICLE_QUEUE_BASE_PATH=./queues \
  java -jar app.jar
```

#### Method 3: System Properties

```bash
# Use ActiveMQ
java -Dmessage-router.queue-type=ACTIVEMQ \
  -Dactivemq.broker.url=tcp://localhost:61616 \
  -jar app.jar

# Use Chronicle Queue
java -Dmessage-router.queue-type=CHRONICLE \
  -Dchronicle.queue.enabled=true \
  -Dchronicle.queue.base-path=./queues \
  -jar app.jar
```

---

## Environment Variables

### Message Router Configuration

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `MESSAGE_ROUTER_CONFIG_URL` | Config endpoint URL (full URL with path) | `http://localhost:8080/api/config` | `http://config-svc:8080/api/config` |
| `MESSAGE_ROUTER_QUEUE_TYPE` | Queue type | `SQS` | `SQS`, `ACTIVEMQ`, `CHRONICLE` |
| `MESSAGE_ROUTER_ENABLED` | Enable message router | `true` | `true`, `false` |

### SQS Configuration

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `SQS_ENDPOINT_OVERRIDE` | SQS endpoint URL (for LocalStack) | *(empty)* | `http://localhost:4566` |
| `AWS_REGION` | AWS region | `eu-west-1` | `us-east-1`, `eu-west-1` |
| `AWS_ACCESS_KEY_ID` | AWS access key | *(from default chain)* | `AKIAIOSFODNN7EXAMPLE` |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key | *(from default chain)* | `wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY` |

### ActiveMQ Configuration

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `ACTIVEMQ_BROKER_URL` | ActiveMQ broker URL | `tcp://localhost:61616` | `tcp://activemq:61616` |
| `ACTIVEMQ_USERNAME` | ActiveMQ username | `admin` | `myuser` |
| `ACTIVEMQ_PASSWORD` | ActiveMQ password | `admin` | `mypassword` |

### Chronicle Queue Configuration

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `CHRONICLE_QUEUE_ENABLED` | Enable Chronicle Queue | `false` | `true`, `false` |
| `CHRONICLE_QUEUE_BASE_PATH` | Base directory for queues | `./chronicle-queues` | `/var/lib/queues` |
| `CHRONICLE_QUEUE_ROLL_CYCLE` | File roll cycle | `HOURLY` | `HOURLY`, `DAILY` |

### Database Configuration (Core App Only)

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `QUARKUS_DATASOURCE_JDBC_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/flowcatalyst` | `jdbc:postgresql://db:5432/mydb` |
| `QUARKUS_DATASOURCE_USERNAME` | Database username | `flowcatalyst` | `myuser` |
| `QUARKUS_DATASOURCE_PASSWORD` | Database password | `flowcatalyst` | `mypassword` |

---

## Profiles Explained

FlowCatalyst uses Quarkus profiles to provide different configurations for different environments.

### Available Profiles

| Profile | Use Case | Queue Type | Database | Logs |
|---------|----------|------------|----------|------|
| `dev` (default) | Local development | SQS (LocalStack) | Required (if using core) | Human-readable |
| `chronicle-dev` | Local dev (no external broker) | Chronicle Queue | Required (if using core) | Human-readable |
| `prod` | Production deployment | SQS/ActiveMQ (configured via env) | Required (if using core) | JSON structured |

### How to Use Profiles

#### In Development Mode (quarkusDev)

```bash
# Default profile (dev)
./gradlew quarkusDev

# Chronicle profile
./gradlew quarkusDev -Dquarkus.profile=chronicle-dev
```

#### With Built JARs

```bash
# Using environment variable
QUARKUS_PROFILE=dev java -jar app.jar

# Using system property
java -Dquarkus.profile=chronicle-dev -jar app.jar
```

#### With Native Binaries

```bash
# Chronicle dev profile is baked into nativeBuildDev
./flowcatalyst-message-router-1.0.0-SNAPSHOT-runner

# Override at runtime
QUARKUS_PROFILE=dev ./flowcatalyst-message-router-1.0.0-SNAPSHOT-runner
```

### Profile vs Environment Variables

**Use profiles when:**
- You want predefined, consistent configurations
- Switching between known environments (dev, prod)
- Building native images with specific config

**Use environment variables when:**
- You need to override specific settings
- Deploying to containers/cloud with dynamic config
- Each deployment has unique connection strings

**Best practice:** Use profiles for base configuration, environment variables for deployment-specific overrides.

```bash
# Example: Use dev profile but override config URL
QUARKUS_PROFILE=dev \
MESSAGE_ROUTER_CONFIG_URL=http://my-config:8080/api/config \
  java -jar app.jar
```

---

## Chronicle Queue (Internal Broker)

Chronicle Queue is an embedded, file-based message queue. Perfect for:
- Local development without external dependencies
- Standalone deployments
- Zero-configuration demos
- Edge deployments

### Development with Chronicle Queue

```bash
# Development mode
./gradlew :flowcatalyst-message-router:quarkusDev -Dquarkus.profile=chronicle-dev

# What you get:
# - Message Router running on http://localhost:8080
# - Chronicle Queue management API at http://localhost:8080/api/chronicle/*
# - Queue files in ./dev-chronicle-queues/
# - No external broker needed!
```

### Build Chronicle Native Binary

```bash
cd flowcatalyst-message-router

# Build developer native image with Chronicle Queue
../gradlew nativeBuildDev

# This creates a self-contained binary that includes Chronicle Queue
# Binary location:
# build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner
```

### Run Chronicle Native Binary

```bash
# Linux/macOS
./flowcatalyst-message-router/build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner

# Windows
.\flowcatalyst-message-router\build\flowcatalyst-message-router-1.0.0-SNAPSHOT-runner.exe

# The binary auto-starts with Chronicle Queue enabled
# Queue files stored in ./chronicle-queues/

# Custom config endpoint
MESSAGE_ROUTER_CONFIG_URL=http://localhost:8000/api/config \
  ./build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner

# Custom queue location
CHRONICLE_QUEUE_BASE_PATH=/var/lib/my-queues \
  ./build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner
```

### Chronicle Queue REST API

The Chronicle native binary exposes a REST API for queue management:

```bash
# Health check
curl http://localhost:8080/api/chronicle/health

# Create queue
curl -X POST http://localhost:8080/api/chronicle/queues/my-queue

# Publish message
curl -X POST http://localhost:8080/api/chronicle/queues/my-queue/messages \
  -H "Content-Type: application/json" \
  -d '{
    "id": "msg-1",
    "poolCode": "TEST-POOL",
    "authToken": "my-token",
    "mediationType": "HTTP",
    "mediationTarget": "http://api.example.com/webhook"
  }'

# Get queue statistics
curl http://localhost:8080/api/chronicle/queues/my-queue/stats

# List all queues
curl http://localhost:8080/api/chronicle/queues

# Delete queue
curl -X DELETE http://localhost:8080/api/chronicle/queues/my-queue
```

### Use from PHP/Python/Other Languages

Since Chronicle Queue exposes a REST API, any language can interact with it:

#### PHP Example

```php
<?php
$url = 'http://localhost:8080/api/chronicle/queues/my-queue/messages';
$data = [
    'id' => 'msg-' . uniqid(),
    'poolCode' => 'PHP-POOL',
    'authToken' => 'token123',
    'mediationType' => 'HTTP',
    'mediationTarget' => 'https://api.example.com/webhook'
];

$options = [
    'http' => [
        'header'  => "Content-Type: application/json\r\n",
        'method'  => 'POST',
        'content' => json_encode($data)
    ]
];

$context = stream_context_create($options);
$result = file_get_contents($url, false, $context);

echo $result;
?>
```

#### Python Example

```python
import requests
import json

url = 'http://localhost:8080/api/chronicle/queues/my-queue/messages'
data = {
    'id': 'msg-12345',
    'poolCode': 'PYTHON-POOL',
    'authToken': 'token123',
    'mediationType': 'HTTP',
    'mediationTarget': 'https://api.example.com/webhook'
}

response = requests.post(url, json=data)
print(response.json())
```

### Chronicle Queue Configuration Files

Chronicle Queue settings are in:
- **Default:** `flowcatalyst-message-router/src/main/resources/application.properties`
- **Dev Profile:** `flowcatalyst-message-router/src/main/resources/application-chronicle-dev.properties`

Key settings:
```properties
# Enable Chronicle Queue
chronicle.queue.enabled=true

# Base directory for queue files
chronicle.queue.base-path=./chronicle-queues

# File roll cycle (HOURLY, DAILY, etc.)
chronicle.queue.roll-cycle=HOURLY

# Queue type must be CHRONICLE
message-router.queue-type=CHRONICLE
```

---

## Troubleshooting

### Issue: "Queue not enabled" error

**Solution:** Ensure Chronicle Queue is enabled:
```bash
# In dev mode
./gradlew quarkusDev -Dquarkus.profile=chronicle-dev

# Or set environment variable
CHRONICLE_QUEUE_ENABLED=true java -jar app.jar
```

### Issue: Can't connect to SQS

**Solution:** Check LocalStack is running:
```bash
docker ps | grep localstack

# Restart if needed
docker restart flowcatalyst-localstack

# Verify SQS endpoint
curl http://localhost:4566/health
```

### Issue: ActiveMQ connection refused

**Solution:** Check ActiveMQ is running:
```bash
docker ps | grep activemq

# Start if needed
docker run -d --name activemq \
  -p 61616:61616 \
  -p 8161:8161 \
  apache/activemq-classic:latest
```

### Issue: Hot reload not working

**Solution:** Use library modules for development:
```bash
# ✅ Correct - supports hot reload
./gradlew :flowcatalyst-message-router:quarkusDev

# ❌ Incorrect - no source code in app modules
./gradlew :flowcatalyst-router-app:quarkusDev
```

### Issue: Native image build fails

**Solution:** Check GraalVM installation:
```bash
# Check Java version
java --version  # Should show GraalVM

# Check native-image
native-image --version

# Install if missing
gu install native-image
```

### Issue: Config endpoint not found

**Solution:** Check the config URL is correct:
```bash
# Test config endpoint
curl http://localhost:8080/api/config

# Set full URL including path
MESSAGE_ROUTER_CONFIG_URL=http://localhost:8000/api/config \
  java -jar app.jar
```

---

## See Also

- [Build Quick Reference](BUILD_QUICK_REFERENCE.md) - Common build commands
- [Main README](README.md) - Project overview and architecture
- [Native Build Guide](flowcatalyst-message-router/NATIVE_BUILD_GUIDE.md) - Detailed native image build instructions
- [Chronicle Queue API](flowcatalyst-message-router/CHRONICLE_QUEUE_API.md) - REST API documentation
- [Chronicle Tests](flowcatalyst-message-router/CHRONICLE_TESTS.md) - Test suite documentation
