# Flow Catalyst

Flow Catalyst is a high-performance message routing and webhook dispatch platform built with Quarkus and Java 21 virtual threads.

## Key Features

- **Message Router** - Process messages from SQS/ActiveMQ with concurrency control and rate limiting
- **Dispatch Jobs** - Reliable webhook delivery with HMAC signing, retries, and full audit trail
- **Virtual Threads** - Efficient I/O handling using Java 21 virtual threads
- **Real-time Monitoring** - Dashboard for queue stats, pool metrics, and health status
- **Modular Architecture** - Deploy router standalone, full-stack, or as microservices

## Architecture

Flow Catalyst uses a modular architecture with **library modules** (reusable components) and **application modules** (deployment bundles):

**Library Modules:**
- `flowcatalyst-auth` - OIDC authentication and security
- `flowcatalyst-message-router` - Stateless message routing (no database)
- `flowcatalyst-core` - Dispatch jobs, webhooks, database persistence

**Application Modules:**
- `flowcatalyst-router-app` - Router-only deployment (lightweight, stateless)
- `flowcatalyst-app` - Full-stack deployment (router + core)

This design enables:
- **Independent Scaling** - Scale router and core separately
- **Flexible Deployment** - Deploy only what you need
- **Clean Dependencies** - Clear separation of concerns
- **Hot Reload** - Works across all modules in dev mode

## Documentation

- **[Architecture](docs/architecture.md)** - System architecture and components
- **[Database Strategy](docs/database-strategy.md)** - Database design decisions and future plans
- **[Dispatch Jobs](docs/dispatch-jobs.md)** - Webhook delivery system details
- **[Testing Guide](docs/TESTING.md)** - Integration and unit testing

## Quick Start

### Prerequisites

**Full-Stack Application (`flowcatalyst-app`):**
- PostgreSQL 16+ (port 5432)
- SQS (AWS or ElasticMQ) OR ActiveMQ Classic (port 61616)

**Router-Only Application (`flowcatalyst-router-app`):**
- SQS (AWS or ElasticMQ) OR ActiveMQ Classic (port 61616)
- No database required!

### Starting Dependencies

```bash
# PostgreSQL (for full mode)
docker run -d --name postgres \
  -e POSTGRES_DB=flowcatalyst \
  -e POSTGRES_USER=flowcatalyst \
  -e POSTGRES_PASSWORD=flowcatalyst \
  -p 5432:5432 \
  postgres:16-alpine

# ElasticMQ (local SQS)
docker run -d --name elasticmq \
  -p 9324:9324 \
  softwaremill/elasticmq-native

# OR ActiveMQ Classic
docker run -d --name activemq \
  -p 61616:61616 \
  -p 8161:8161 \
  apache/activemq-classic:latest
```

## Development vs Deployment

**IMPORTANT:** Application modules (`flowcatalyst-router-app`, `flowcatalyst-app`) are **thin wrappers for deployment only**. They contain no source code - just configuration and dependency bundling.

**For development, always use the library modules:**
- `:flowcatalyst-message-router:quarkusDev` - Router development
- `:flowcatalyst-app:quarkusDev` - Full-stack development (includes both router and core)

## Development Scenarios

### Scenario 1: Message Router Development

Develop router features (consumers, pools, rate limiting). **No database required!**

```bash
# Run from project root
./gradlew :flowcatalyst-message-router:quarkusDev
```

**What you get:**
- ✅ Message Router (SQS/ActiveMQ)
- ✅ Processing Pools with concurrency control
- ✅ Rate limiting and circuit breaker
- ✅ Monitoring dashboard and health endpoints
- ✅ Hot reload on code changes
- ❌ Dispatch Jobs (not included in this module)
- ❌ PostgreSQL (not needed for router)

### Scenario 2: Full-Stack Development

Develop both router and core features together. **Requires PostgreSQL**.

```bash
# Run from project root
./gradlew :flowcatalyst-app:quarkusDev
```

**What you get:**
- ✅ Message Router (SQS/ActiveMQ)
- ✅ Dispatch Jobs (webhook delivery)
- ✅ Database persistence (PostgreSQL)
- ✅ All monitoring and health endpoints
- ✅ Hot reload for changes in both router and core modules

### Scenario 3: Core-Only Development

Develop dispatch jobs and core business logic. **Requires PostgreSQL**.

```bash
# Run from project root
./gradlew :flowcatalyst-core:quarkusDev
```

**What you get:**
- ✅ Dispatch Jobs (webhook delivery)
- ✅ Database persistence (PostgreSQL)
- ✅ Hot reload on code changes
- ⚠️ Note: Router features are dependencies but config comes from core

## Deployment Scenarios

### Production: Router-Only Deployment

Lightweight stateless router without dispatch jobs. **No database required!**

```bash
# Build the deployment artifact
./gradlew :flowcatalyst-router-app:build

# Run in production
java -jar flowcatalyst-router-app/build/quarkus-app/quarkus-run.jar
```

### Production: Full-Stack Deployment

Everything together - Message Router + Dispatch Jobs. **Requires PostgreSQL**.

```bash
# Build the deployment artifact
./gradlew :flowcatalyst-app:build

# Run in production
java -jar flowcatalyst-app/build/quarkus-app/quarkus-run.jar
```

## Quick Start Commands

### For Development (with hot reload)

```bash
# Message Router only (no database needed)
./gradlew :flowcatalyst-message-router:quarkusDev

# Full-Stack (requires PostgreSQL running)
./gradlew :flowcatalyst-app:quarkusDev

# Core only (requires PostgreSQL running)
./gradlew :flowcatalyst-core:quarkusDev
```

**Available endpoints:**
- Application: http://localhost:8080
- Dashboard: http://localhost:8080/dashboard.html
- Dev UI: http://localhost:8080/q/dev (live testing, config editor)
- Health: http://localhost:8080/health/ready
- Metrics: http://localhost:8080/metrics

### For Production Deployment

```bash
# Router-only (no database)
./gradlew :flowcatalyst-router-app:build
java -jar flowcatalyst-router-app/build/quarkus-app/quarkus-run.jar

# Full-stack (requires PostgreSQL)
./gradlew :flowcatalyst-app:build
java -jar flowcatalyst-app/build/quarkus-app/quarkus-run.jar
```

## Packaging and Running

### Building Applications

```bash
# Build all modules
./gradlew build

# Build specific application
./gradlew :flowcatalyst-app:build              # Full-stack
./gradlew :flowcatalyst-router-app:build       # Router-only

# Build library modules
./gradlew :flowcatalyst-message-router:build
./gradlew :flowcatalyst-core:build
```

The build produces a `quarkus-run.jar` in each module's `build/quarkus-app/` directory.

### Running Applications

```bash
# Full-stack application
java -jar flowcatalyst-app/build/quarkus-app/quarkus-run.jar

# Router-only application
java -jar flowcatalyst-router-app/build/quarkus-app/quarkus-run.jar
```

### Über-jar Build

For a single executable JAR with all dependencies:

```bash
# Full-stack
./gradlew :flowcatalyst-app:build -Dquarkus.package.jar.type=uber-jar

# Router-only
./gradlew :flowcatalyst-router-app:build -Dquarkus.package.jar.type=uber-jar
```

## Native Executables

Create native executables with GraalVM for faster startup and lower memory usage:

```bash
# Full-stack application
./gradlew :flowcatalyst-app:build -Dquarkus.native.enabled=true

# Router-only application
./gradlew :flowcatalyst-router-app:build -Dquarkus.native.enabled=true

# Build in container (no GraalVM installation needed)
./gradlew :flowcatalyst-app:build \
  -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true
```

Run native executables:

```bash
# Full-stack
./flowcatalyst-app/build/flowcatalyst-app-1.0.0-SNAPSHOT-runner

# Router-only
./flowcatalyst-router-app/build/flowcatalyst-router-app-1.0.0-SNAPSHOT-runner
```

Learn more: [Quarkus Native Guide](https://quarkus.io/guides/gradle-tooling)

## Docker Deployment

### Building Container Images

```bash
# Full-stack application
./gradlew :flowcatalyst-app:build \
  -Dquarkus.container-image.build=true

# Router-only application
./gradlew :flowcatalyst-router-app:build \
  -Dquarkus.container-image.build=true
```

### Running Containers

**Full-Stack Application:**
```bash
docker run -p 8080:8080 \
  -e POSTGRES_URL=jdbc:postgresql://host.docker.internal:5432/flowcatalyst \
  -e POSTGRES_USER=flowcatalyst \
  -e POSTGRES_PASSWORD=flowcatalyst \
  -e MESSAGE_ROUTER_CONFIG_URL=http://config-service:8080 \
  -e SQS_ENDPOINT_OVERRIDE=http://sqs:9324 \
  flowcatalyst-app:1.0.0-SNAPSHOT
```

**Router-Only Application:**
```bash
docker run -p 8080:8080 \
  -e MESSAGE_ROUTER_CONFIG_URL=http://config-service:8080 \
  -e SQS_ENDPOINT_OVERRIDE=http://sqs:9324 \
  flowcatalyst-router-app:1.0.0-SNAPSHOT
```

### Microservices Deployment

Deploy router and core as separate services:

**docker-compose.yml:**
```yaml
services:
  router:
    image: flowcatalyst-router-app:1.0.0-SNAPSHOT
    ports:
      - "8081:8080"
    environment:
      - SQS_ENDPOINT_OVERRIDE=http://localstack:4566

  core:
    image: flowcatalyst-app:1.0.0-SNAPSHOT
    ports:
      - "8080:8080"
    environment:
      - POSTGRES_URL=jdbc:postgresql://postgres:5432/flowcatalyst
      - MESSAGE_ROUTER_CONFIG_URL=http://router:8080
    depends_on:
      - postgres
```

## Running Tests

### Quick Start

```bash
# Run all tests in all modules
./gradlew test

# Test specific modules
./gradlew :flowcatalyst-message-router:test
./gradlew :flowcatalyst-core:test

# Run integration tests
./gradlew :flowcatalyst-core:integrationTest

# Run unit tests only (fast)
./gradlew test -x integrationTest
```

### Specific Tests

```bash
# Run a specific test class
./gradlew :flowcatalyst-message-router:test --tests QueueManagerTest

# Run a specific test method
./gradlew :flowcatalyst-message-router:test --tests ProcessPoolImplTest.shouldEnforceRateLimit

# Run with verbose output
./gradlew test --info
```

### Test Suite Overview

**Module Structure:**
- `flowcatalyst-message-router` - Router unit and integration tests
- `flowcatalyst-core` - Core business logic tests
- Application modules have no tests (thin wrappers)

**Test Types:**
- **Unit Tests** - Fast, isolated component testing with mocks
- **Integration Tests** - Real dependencies (SQS via LocalStack, PostgreSQL via Testcontainers)
- **REST Endpoint Tests** - API contract validation
- **Metrics Tests** - Monitoring and observability validation

For detailed testing documentation, see:
- [Test Suite Summary](TEST_SUMMARY.md) - Overview of all tests
- [Testing Guide](docs/TESTING.md) - Comprehensive testing guide

## Development Workflows

### Working on Message Router Features

When developing router-specific features (consumers, pools, rate limiting):

```bash
# Start message router in dev mode (no database needed!)
./gradlew :flowcatalyst-message-router:quarkusDev

# In another terminal, run tests
./gradlew :flowcatalyst-message-router:test

# Your changes automatically hot reload
```

**Why use the library module?**
- Direct access to router source code
- Fast hot reload
- No database required
- Isolated testing of router features

### Working on Core Features (Dispatch Jobs)

When developing core features (webhooks, dispatch jobs, database):

```bash
# Start core in dev mode (requires PostgreSQL running)
./gradlew :flowcatalyst-core:quarkusDev

# In another terminal, run tests
./gradlew :flowcatalyst-core:test
./gradlew :flowcatalyst-core:integrationTest

# Your changes automatically hot reload
```

### Testing Full Integration

To test how router and core work together as a complete system:

```bash
# Run the full-stack application (NOT the router-app or app modules)
./gradlew :flowcatalyst-app:quarkusDev

# Both router and core features are active
# Changes to either library module hot reload automatically
```

**Note:** The `:flowcatalyst-app` module bundles both `:flowcatalyst-message-router` and `:flowcatalyst-core`, so Quarkus watches all source files across both modules.

### Module Dependencies

```
flowcatalyst-app
├── flowcatalyst-core
│   ├── flowcatalyst-message-router
│   │   └── flowcatalyst-auth
│   └── flowcatalyst-auth
└── flowcatalyst-message-router (direct)

flowcatalyst-router-app
└── flowcatalyst-message-router
    └── flowcatalyst-auth
```

### Configuration Priority

Configuration files are loaded in this order (later overrides earlier):

1. Library module `application.properties` (e.g., `flowcatalyst-message-router/src/main/resources/application.properties`)
2. Application module `application.properties` (e.g., `flowcatalyst-app/src/main/resources/application.properties`)
3. Environment variables (e.g., `POSTGRES_URL`)
4. System properties (e.g., `-Dquarkus.http.port=9090`)

### Common Commands

```bash
# Clean build everything
./gradlew clean build

# Quick compile check (no tests)
./gradlew classes

# Watch for compilation errors
./gradlew --continuous classes

# See all modules
./gradlew projects

# Check dependencies
./gradlew :flowcatalyst-app:dependencies
./gradlew :flowcatalyst-message-router:dependencies
```

## Project Structure

```
flowcatalyst/
├── flowcatalyst-auth/              # Library: OIDC authentication
├── flowcatalyst-message-router/    # Library: Stateless message router
│   ├── src/main/java/.../messagerouter/
│   │   ├── consumer/              # Queue consumers (SQS, ActiveMQ)
│   │   ├── pool/                  # Processing pools
│   │   ├── manager/               # Pool and queue management
│   │   ├── health/                # Health checks
│   │   └── metrics/               # Monitoring and metrics
│   └── src/test/                  # Router tests
├── flowcatalyst-core/              # Library: Core business logic
│   ├── src/main/java/.../core/
│   │   ├── dispatchjob/           # Webhook dispatch system
│   │   └── mediator/              # Message mediation
│   └── src/test/                  # Core tests
├── flowcatalyst-router-app/        # Application: Router-only deployment
│   └── src/main/resources/
│       └── application.properties # Router app config
├── flowcatalyst-app/               # Application: Full-stack deployment
│   └── src/main/resources/
│       └── application.properties # Full-stack config
└── settings.gradle.kts            # Module definitions
```

## Performance Characteristics

| Module | Startup Time | Memory (RSS) | Database | Dependencies |
|--------|-------------|--------------|----------|--------------|
| flowcatalyst-router-app | ~2s | ~200MB | None | Queue only |
| flowcatalyst-app | ~3s | ~350MB | PostgreSQL | Queue + DB |

*Native builds reduce startup to <100ms and memory by ~50%*

## Troubleshooting

### Error: "The project has no output yet" when running quarkusDev

**Problem:** You tried to run `./gradlew :flowcatalyst-router-app:quarkusDev` or similar on an application module.

**Solution:** Application modules (`flowcatalyst-router-app`, `flowcatalyst-app`) are thin wrappers with no source code - they're for deployment only. For development, use the library modules:

```bash
# ❌ DON'T: Application modules can't run in dev mode
./gradlew :flowcatalyst-router-app:quarkusDev

# ✅ DO: Use library modules for development
./gradlew :flowcatalyst-message-router:quarkusDev  # Router development
./gradlew :flowcatalyst-app:quarkusDev              # Full-stack development
```

### Port 8080 already in use

```bash
# Kill process on port 8080
lsof -ti:8080 | xargs kill -9

# Or use a different port
./gradlew :flowcatalyst-message-router:quarkusDev -Dquarkus.http.port=8081
```

### PostgreSQL connection errors

Make sure PostgreSQL is running and accessible:

```bash
# Check if PostgreSQL is running
docker ps | grep postgres

# Start PostgreSQL if needed
docker run -d --name postgres \
  -e POSTGRES_DB=flowcatalyst \
  -e POSTGRES_USER=flowcatalyst \
  -e POSTGRES_PASSWORD=flowcatalyst \
  -p 5432:5432 \
  postgres:16-alpine
```

**Tip:** Use `:flowcatalyst-message-router:quarkusDev` for router development - it doesn't need PostgreSQL!

### Module not found errors

```bash
# Clean and rebuild all modules
./gradlew clean build

# Verify all modules are recognized
./gradlew projects
```

## Related Guides

- REST ([guide](https://quarkus.io/guides/rest)): A Jakarta REST implementation utilizing build time processing and Vert.x. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it.
- REST Jackson ([guide](https://quarkus.io/guides/rest#json-serialisation)): Jackson serialization support for Quarkus REST. This extension is not compatible with the quarkus-resteasy extension, or any of the extensions that depend on it
