# FlowCatalyst Rust Binaries

This document describes the production binaries and their configuration.

## Quick Start - Development

```bash
# Install dependencies
npm install

# Start MongoDB (uses docker-compose.dev.yml)
npm run dev:db

# Start platform server + stream processor
npm run dev

# Or use the shell script
./dev.sh
```

### Available npm Scripts

| Command | Description |
|---------|-------------|
| `npm run dev` | Start platform + stream processor |
| `npm run dev:full` | Start MongoDB + all services |
| `npm run dev:watch` | Start with auto-reload (requires cargo-watch) |
| `npm run dev:platform` | Start platform server only |
| `npm run dev:stream-processor` | Start stream processor only |
| `npm run dev:db` | Start MongoDB |
| `npm run dev:db:stop` | Stop MongoDB |
| `npm run build` | Build release binaries |
| `npm run start` | Run release binaries |

### Using the Shell Script

```bash
./dev.sh              # Start MongoDB + all services
./dev.sh platform     # Start platform with auto-reload
./dev.sh stream       # Start stream processor with auto-reload
./dev.sh db           # Start MongoDB only
./dev.sh db:stop      # Stop MongoDB
./dev.sh build        # Build release binaries
./dev.sh help         # Show all commands
```

### Development URLs

| Service | URL |
|---------|-----|
| Platform API | http://localhost:8080 |
| Platform Health | http://localhost:8080/health |
| Platform Metrics | http://localhost:9090/metrics |
| Stream Processor Metrics | http://localhost:9091/metrics |
| Mongo Express (optional) | http://localhost:8081 |

---

## Binary Overview

| Binary | Description | Use Case |
|--------|-------------|----------|
| `fc-dev` | All-in-one development monolith | Local development |
| `fc-router` | Message router | Production - consumes from SQS, dispatches to webhooks |
| `fc-platform-server` | Platform REST APIs | Production - events, subscriptions, admin APIs |
| `fc-outbox-processor` | Outbox processor | Production - reads outbox tables, publishes to SQS |
| `fc-stream-processor` | MongoDB change stream processor | Production - watches events, creates dispatch jobs |

---

## fc-dev (Development Monolith)

All-in-one binary for local development. Runs everything in a single process.

### Build & Run

```bash
cargo build -p fc-dev
./target/debug/fc-dev
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FC_ROUTER_PORT` | `8081` | Router API port |
| `FC_API_PORT` | `8080` | Platform API port |
| `FC_METRICS_PORT` | `9090` | Metrics/health port |
| `FC_MONGO_URL` | `mongodb://localhost:27017` | MongoDB connection URL |
| `FC_MONGO_DB` | `flowcatalyst` | MongoDB database name |
| `FC_QUEUE_PATH` | `:memory:` | SQLite queue path (`:memory:` for in-memory) |
| `FC_OUTBOX_ENABLED` | `false` | Enable outbox processor |
| `FC_OUTBOX_DB_TYPE` | `sqlite` | Outbox database: `sqlite`, `postgres`, `mongo` |
| `FC_OUTBOX_DB_URL` | - | Outbox database URL |
| `FC_OUTBOX_POLL_INTERVAL_MS` | `1000` | Outbox poll interval |
| `RUST_LOG` | `info` | Log level |

### Features

- Embedded SQLite queue (mimics SQS FIFO)
- HTTP/1.1 mediator for local testing
- All platform APIs mounted
- Auto-generates RSA keys for JWT (persisted to `.jwt-keys/`)
- Optional outbox processing

---

## fc-router (Message Router)

Production message router. Consumes from AWS SQS and dispatches to webhook endpoints.

### Build & Run

```bash
cargo build -p fc-router-bin --release
./target/release/fc-router-bin
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `QUEUE_URL` | `http://localhost:4566/...` | SQS queue URL |
| `VISIBILITY_TIMEOUT` | `30` | SQS visibility timeout (seconds) |
| `POOL_CONCURRENCY` | `10` | Concurrent dispatches per pool |
| `API_PORT` | `8080` | HTTP API port |
| `RUST_LOG` | `info` | Log level |

### Features

- HTTP/2 with 15-minute timeouts (production mode)
- Rate limiting per dispatch pool
- Circuit breaker support
- Graceful shutdown with visibility extension
- Health and metrics endpoints

### Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Health check |
| `GET /metrics` | Prometheus metrics |
| `POST /api/messages` | Publish message to queue |
| `GET /api/pools` | List processing pools |

---

## fc-platform-server (Platform APIs)

Production server for platform REST APIs.

### Build & Run

```bash
cargo build -p fc-platform-server --release
./target/release/fc-platform-server
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FC_API_PORT` | `8080` | HTTP API port |
| `FC_METRICS_PORT` | `9090` | Metrics/health port |
| `FC_MONGO_URL` | `mongodb://localhost:27017` | MongoDB connection URL |
| `FC_MONGO_DB` | `flowcatalyst` | MongoDB database name |
| `FC_JWT_PRIVATE_KEY_PATH` | - | Path to RSA private key PEM file |
| `FC_JWT_PUBLIC_KEY_PATH` | - | Path to RSA public key PEM file |
| `FLOWCATALYST_JWT_PRIVATE_KEY` | - | RSA private key PEM content (env) |
| `FLOWCATALYST_JWT_PUBLIC_KEY` | - | RSA public key PEM content (env) |
| `FC_JWT_ISSUER` | `flowcatalyst` | JWT issuer claim |
| `RUST_LOG` | `info` | Log level |

### JWT Key Configuration

Keys can be provided in three ways (in order of precedence):

1. **File paths**: Set `FC_JWT_PRIVATE_KEY_PATH` and `FC_JWT_PUBLIC_KEY_PATH`
2. **Environment variables**: Set `FLOWCATALYST_JWT_PRIVATE_KEY` and `FLOWCATALYST_JWT_PUBLIC_KEY` with PEM content
3. **Auto-generation**: If neither is set, keys are generated and persisted to `.jwt-keys/`

Generate production keys:
```bash
openssl genrsa -out jwt-private.pem 2048
openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem
```

### API Endpoints

#### BFF APIs (Backend-for-Frontend)
| Endpoint | Description |
|----------|-------------|
| `/api/bff/events` | Event list/detail |
| `/api/bff/event-types` | Event type list |
| `/api/bff/dispatch-jobs` | Dispatch job list/detail |
| `/api/bff/filter-options` | Filter dropdown options |

#### Admin APIs
| Endpoint | Description |
|----------|-------------|
| `/api/admin/clients` | Client management |
| `/api/admin/principals` | User/service account management |
| `/api/admin/roles` | Role management |
| `/api/admin/subscriptions` | Subscription management |
| `/api/admin/oauth-clients` | OAuth client management |
| `/api/admin/anchor-domains` | Anchor domain configuration |
| `/api/admin/client-auth-configs` | Client auth configuration |
| `/api/admin/idp-role-mappings` | IDP role mappings |
| `/api/admin/audit-logs` | Audit log access |
| `/api/admin/applications` | Application management |
| `/api/admin/dispatch-pools` | Dispatch pool management |

#### Monitoring APIs
| Endpoint | Description |
|----------|-------------|
| `/api/monitoring/health` | Health status |
| `/api/monitoring/leader` | Leader election status |
| `/api/monitoring/circuit-breakers` | Circuit breaker states |
| `/api/monitoring/in-flight` | In-flight requests |

---

## fc-outbox-processor (Outbox Processor)

Reads messages from application database outbox tables and publishes to SQS.

### Build & Run

```bash
cargo build -p fc-outbox-processor --release
./target/release/fc-outbox-processor
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FC_OUTBOX_DB_TYPE` | `postgres` | Database type: `sqlite`, `postgres`, `mongo` |
| `FC_OUTBOX_DB_URL` | **required** | Database connection URL |
| `FC_OUTBOX_MONGO_DB` | `flowcatalyst` | MongoDB database name (if mongo) |
| `FC_OUTBOX_MONGO_COLLECTION` | `outbox` | MongoDB collection name (if mongo) |
| `FC_OUTBOX_POLL_INTERVAL_MS` | `1000` | Poll interval in milliseconds |
| `FC_OUTBOX_BATCH_SIZE` | `100` | Max messages per batch |
| `FC_QUEUE_URL` | **required** | SQS queue URL |
| `FC_METRICS_PORT` | `9090` | Metrics/health port |
| `RUST_LOG` | `info` | Log level |

### Database Connection Examples

**SQLite:**
```bash
FC_OUTBOX_DB_TYPE=sqlite FC_OUTBOX_DB_URL=sqlite:./outbox.db
```

**PostgreSQL:**
```bash
FC_OUTBOX_DB_TYPE=postgres FC_OUTBOX_DB_URL=postgres://user:pass@localhost/mydb
```

**MongoDB:**
```bash
FC_OUTBOX_DB_TYPE=mongo FC_OUTBOX_DB_URL=mongodb://localhost:27017 FC_OUTBOX_MONGO_DB=myapp
```

---

## fc-stream-processor (Stream Processor)

Watches MongoDB change streams for new events and creates dispatch jobs.

### Build & Run

```bash
cargo build -p fc-stream-processor --release
./target/release/fc-stream-processor
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FC_MONGO_URL` | `mongodb://localhost:27017` | MongoDB connection URL |
| `FC_MONGO_DB` | `flowcatalyst` | MongoDB database name |
| `FC_METRICS_PORT` | `9090` | Metrics/health port |
| `FC_STREAM_BATCH_SIZE` | `100` | Max events to process per batch |
| `RUST_LOG` | `info` | Log level |

### Features

- Watches `events` collection for inserts
- Matches events to active subscriptions
- Supports wildcard event type patterns (e.g., `orders:*:*:*`)
- Creates dispatch jobs with proper linkage
- Graceful shutdown with change stream resume

### Event Type Matching

Subscriptions can use wildcard patterns:
- `orders:fulfillment:shipment:shipped` - Exact match
- `orders:fulfillment:*:*` - Matches any shipment event
- `orders:*:*:*` - Matches any orders event
- `*:*:*:*` - Matches all events

---

## Docker Deployment

### Example Dockerfile

```dockerfile
FROM rust:1.75-slim as builder
WORKDIR /app
COPY . .
RUN cargo build --release -p fc-platform-server

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/target/release/fc-platform-server /usr/local/bin/
EXPOSE 8080 9090
CMD ["fc-platform-server"]
```

### Kubernetes Secrets for JWT Keys

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: jwt-keys
type: Opaque
stringData:
  private.pem: |
    -----BEGIN PRIVATE KEY-----
    ...
    -----END PRIVATE KEY-----
  public.pem: |
    -----BEGIN PUBLIC KEY-----
    ...
    -----END PUBLIC KEY-----
```

Mount as files or use as env vars:

```yaml
env:
  - name: FLOWCATALYST_JWT_PRIVATE_KEY
    valueFrom:
      secretKeyRef:
        name: jwt-keys
        key: private.pem
  - name: FLOWCATALYST_JWT_PUBLIC_KEY
    valueFrom:
      secretKeyRef:
        name: jwt-keys
        key: public.pem
```

---

## Health Checks

All binaries provide health endpoints:

| Endpoint | Response | Description |
|----------|----------|-------------|
| `/health` | `{"status": "UP"}` | Basic health check |
| `/ready` | `{"status": "READY"}` | Readiness check |
| `/metrics` | Prometheus format | Metrics endpoint |

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 9090
  initialDelaySeconds: 10
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /ready
    port: 9090
  initialDelaySeconds: 5
  periodSeconds: 5
```
