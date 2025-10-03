# Build Quick Reference

## Module Structure

FlowCatalyst uses a modular structure:
- `core/*` - Java/Quarkus platform modules
- `clients/*` - Multi-language SDKs
- `packages/*` - Frontend (Bun workspace)

## Common Commands

### Development Mode (Hot Reload)

```bash
# Message Router only (no database)
./gradlew :core:flowcatalyst-message-router:quarkusDev

# Full platform (router + core)
./gradlew :core:flowcatalyst-app:quarkusDev

# Control Plane (BFFE + UI)
./gradlew :core:flowcatalyst-control-plane-bffe:quarkusDev

# With Chronicle Queue
./gradlew :core:flowcatalyst-message-router:quarkusDev -Dquarkus.profile=chronicle-dev
```

### Building

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :core:flowcatalyst-message-router:build
./gradlew :core:flowcatalyst-core:build
./gradlew :core:flowcatalyst-app:build

# Skip tests
./gradlew build -x test
```

### Testing

```bash
# All tests (all modules)
./gradlew test

# Module-specific tests
./gradlew :core:flowcatalyst-message-router:test
./gradlew :core:flowcatalyst-core:test

# Integration tests
./gradlew :core:flowcatalyst-message-router:integrationTest
./gradlew :core:flowcatalyst-core:integrationTest

# Single test
./gradlew :core:flowcatalyst-message-router:test --tests 'QueueManagerTest'
```

### Native Builds (Slow, for Distribution)

```bash
# Message Router native
./gradlew :core:flowcatalyst-message-router:build -Dquarkus.package.type=native -x test

# Full app native
./gradlew :core:flowcatalyst-app:build -Dquarkus.package.type=native -x test

# With optimization
./gradlew :core:flowcatalyst-message-router:build \
  -Dquarkus.package.type=native \
  -Dquarkus.native.additional-build-args="-O3" \
  -x test
```

### Run Binaries

```bash
# Linux/macOS
./core/flowcatalyst-message-router/build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner
./core/flowcatalyst-app/build/flowcatalyst-app-1.0.0-SNAPSHOT-runner

# Windows
.\core\flowcatalyst-message-router\build\flowcatalyst-message-router-1.0.0-SNAPSHOT-runner.exe
```

### Run with LocalStack (Local Development)

Run the message router JAR with all required environment variables for local development:

```bash
# Message Router with LocalStack + custom config endpoint + HTTP/1.1
SQS_ENDPOINT_OVERRIDE=http://localhost:4566 \
  AWS_REGION=eu-west-1 \
  AWS_ACCESS_KEY_ID=test \
  AWS_SECRET_ACCESS_KEY=test \
  MESSAGE_ROUTER_CONFIG_URL=http://localhost:8000/api/config \
  QUARKUS_REST_CLIENT_MESSAGE_ROUTER_CONFIG_CONNECT_TIMEOUT=10000 \
  QUARKUS_REST_CLIENT_MESSAGE_ROUTER_CONFIG_READ_TIMEOUT=120000 \
  CONFIG_CLIENT_HTTP2=false \
  MEDIATOR_HTTP_VERSION=HTTP_1_1 \
    java -jar core/flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar
```

**Environment Variables Explained:**
- `SQS_ENDPOINT_OVERRIDE` - LocalStack SQS endpoint (default port 4566)
- `AWS_REGION` - AWS region for LocalStack
- `AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY` - Test credentials for LocalStack
- `MESSAGE_ROUTER_CONFIG_URL` - Custom config endpoint URL (overrides default)
- `QUARKUS_REST_CLIENT_MESSAGE_ROUTER_CONFIG_CONNECT_TIMEOUT` - Config API connect timeout (ms)
- `QUARKUS_REST_CLIENT_MESSAGE_ROUTER_CONFIG_READ_TIMEOUT` - Config API read timeout (ms)
- `CONFIG_CLIENT_HTTP2` - Config REST client HTTP/2 support (`true` default, set `false` for HTTP/1.1)
- `MEDIATOR_HTTP_VERSION` - Mediator HTTP version (`HTTP_2` default, set `HTTP_1_1` to override)

## GitHub Actions

### Trigger Manual Build

1. Go to: **Actions** → **Build Native Images** → **Run workflow**
2. Select branch and options
3. Download artifacts after build completes

### Create Release

```bash
# Tag version
git tag v1.0.0
git push origin v1.0.0

# GitHub Actions automatically:
# 1. Builds all platforms
# 2. Creates GitHub release
# 3. Uploads binaries
```

## Frontend Development

```bash
# Install dependencies
cd packages && bun install

# Develop UI components
bun run ui:dev

# Develop Control Plane
bun run control-plane:dev

# Build all packages
bun run build
```

## Client SDK Development

```bash
# TypeScript
cd clients/typescript/flowcatalyst-sdk
npm install && npm run build

# Python
cd clients/python/flowcatalyst-sdk
pip install -e ".[dev]"

# Go
cd clients/go/flowcatalyst-sdk
go build
```

## Docker Services

```bash
# Start all services
docker-compose up -d

# Stop all services
docker stop flowcatalyst-keycloak flowcatalyst-postgres \
  flowcatalyst-activemq flowcatalyst-postgres-keycloak flowcatalyst-localstack

# Restart LocalStack
docker restart flowcatalyst-localstack
```

## Troubleshooting

```bash
# Clean build all modules
./gradlew clean build

# Clean specific module
./gradlew :core:flowcatalyst-message-router:clean
./gradlew :core:flowcatalyst-message-router:build

# Verbose native build
./gradlew :core:flowcatalyst-message-router:build \
  -Dquarkus.package.type=native \
  -Dquarkus.native.additional-build-args="--verbose"

# Check versions
native-image --version
java --version
bun --version
```

## Binary Locations

**JVM Mode:**
- `core/flowcatalyst-message-router/build/quarkus-app/quarkus-run.jar`
- `core/flowcatalyst-app/build/quarkus-app/quarkus-run.jar`

**Native Mode:**
- Linux/macOS: `core/flowcatalyst-message-router/build/flowcatalyst-message-router-1.0.0-SNAPSHOT-runner`
- Windows: `core\flowcatalyst-message-router\build\flowcatalyst-message-router-1.0.0-SNAPSHOT-runner.exe`

## Configuration Files

- **Default:** `src/main/resources/application.properties`
- **Chronicle Dev:** `src/main/resources/application-chronicle-dev.properties`
- **Override:** Set environment variables or `-D` flags

## Chronicle Queue API

```bash
# Health check
curl http://localhost:8080/api/chronicle/health

# Create queue
curl -X POST http://localhost:8080/api/chronicle/queues/my-queue

# Publish message
curl -X POST http://localhost:8080/api/chronicle/queues/my-queue/messages \
  -H "Content-Type: application/json" \
  -d '{"id":"msg-1","poolCode":"TEST","authToken":"abc","mediationType":"HTTP","mediationTarget":"http://localhost:9000/api"}'

# Get stats
curl http://localhost:8080/api/chronicle/queues/my-queue/stats
```

## Useful Flags

| Flag | Description |
|------|-------------|
| `-x test` | Skip tests |
| `-Dquarkus.package.type=native` | Build native image |
| `-Dquarkus.profile=chronicle-dev` | Use Chronicle profile |
| `-Dquarkus.native.additional-build-args="--verbose"` | Verbose native build |
| `--rerun-tasks` | Force re-run all tasks |
| `--info` | Show info logs |
| `--debug` | Show debug logs |

## Quick Links

### Core Platform
- [Native Build Guide](./core/flowcatalyst-message-router/NATIVE_BUILD_GUIDE.md)
- [Chronicle Queue API](./core/flowcatalyst-message-router/CHRONICLE_QUEUE_API.md)
- [Developer Guide](./DEVELOPER_GUIDE.md)

### Frontend
- [UI Components](./packages/ui-components/README.md)
- [Control Plane](./packages/control-plane/README.md)

### Client SDKs
- [TypeScript SDK](./clients/typescript/flowcatalyst-sdk/README.md)
- [Python SDK](./clients/python/flowcatalyst-sdk/README.md)
- [Go SDK](./clients/go/flowcatalyst-sdk/README.md)

### CI/CD
- [GitHub Actions Workflow](./.github/workflows/native-build.yml)
