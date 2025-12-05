# FlowCatalyst

**Open Source Platform for Building Event-Driven, Domain-Oriented Applications**

FlowCatalyst is a high-performance message routing and webhook dispatch platform built with Quarkus and Java 21 virtual threads. It provides the core infrastructure for building event-driven applications with reliable message processing, webhook delivery, and platform management tools.

## Platform Overview

FlowCatalyst is designed as an **open source platform** with opt-in components:

### Core Platform (Java/Quarkus)
The runtime services that power event-driven applications:
- **Message Router** - High-throughput message processing with SQS/ActiveMQ
- **Core Services** - Dispatch jobs, webhooks, and database persistence
- **Authentication** - OIDC-based security (optional)
- **Control Plane BFFE** - Backend for platform management UI

### Client SDKs
Multi-language support for platform integration:
- **TypeScript/JavaScript** - `@flowcatalyst/sdk` (npm)
- **Python** - `flowcatalyst-sdk` (PyPI)
- **Go** - `github.com/yourusername/flowcatalyst-sdk-go`
- **Java** - Built into platform modules

### UI Components (Optional)
Reusable Vue 3 components for building platform UIs:
- **Component Library** - `@flowcatalyst/ui-components` (npm)
- Built with Vue 3, TypeScript, and Tailwind CSS
- Ready for Tailwind UI Plus integration

### Platform Applications
Reference applications for platform management:
- **Control Plane** - Manage event types, subscriptions, and dispatch jobs
- **Monitoring Dashboard** - Real-time metrics and health status

## Repository Structure

```
flowcatalyst/
├── core/                          # Java/Quarkus platform modules
│   ├── flowcatalyst-core/        # Dispatch jobs, webhooks, persistence
│   ├── flowcatalyst-message-router/  # Message routing engine
│   ├── flowcatalyst-auth/        # OIDC authentication
│   ├── flowcatalyst-router-app/  # Router-only deployment
│   ├── flowcatalyst-app/         # Full-stack deployment
│   └── flowcatalyst-control-plane-bffe/  # Control plane backend
│
├── clients/                       # Client SDKs
│   ├── typescript/flowcatalyst-sdk/   # TypeScript/JS SDK
│   ├── python/flowcatalyst-sdk/       # Python SDK
│   └── go/flowcatalyst-sdk/           # Go SDK
│
└── packages/                      # Frontend packages (Bun workspace)
    ├── ui-components/            # Shared Vue 3 component library
    └── control-plane/            # Control plane Vue application
```

## Key Features

- **High Performance** - Java 21 virtual threads for efficient I/O handling
- **Message Group FIFO Ordering** - Strict ordering per business entity, concurrent across entities (see [MESSAGE_GROUP_FIFO.md](docs/MESSAGE_GROUP_FIFO.md))
- **Modular Architecture** - Use only what you need
- **Multi-Language SDKs** - TypeScript, Python, Go, and Java support
- **Reliable Delivery** - HMAC signing, retries, and full audit trail
- **Real-time Monitoring** - Dashboard for queue stats and health status
- **Flexible Deployment** - Standalone router, full-stack, or microservices

## Quick Start

### Running the Platform

**Prerequisites:**
- Java 21+
- PostgreSQL 16+ (for full mode)
- SQS (AWS or ElasticMQ) or ActiveMQ

**Start Core Services:**
```bash
# Full-stack platform (router + core + webhooks)
./gradlew :core:flowcatalyst-app:quarkusDev

# Router-only (no database required)
./gradlew :core:flowcatalyst-router-app:quarkusDev
```

**Start Control Plane:**
```bash
# Control plane (management UI + API)
./gradlew :core:flowcatalyst-control-plane-bffe:quarkusDev
# Visit http://localhost:8080
```

### Using the SDKs

**TypeScript/JavaScript:**
```bash
npm install @flowcatalyst/sdk
```

```typescript
import { FlowCatalystClient } from '@flowcatalyst/sdk'

const client = new FlowCatalystClient({
  baseUrl: 'http://localhost:8080',
})

const { data: eventTypes } = await client.getEventTypes()
```

**Python:**
```bash
pip install flowcatalyst-sdk
```

```python
from flowcatalyst import FlowCatalystClient

client = FlowCatalystClient(base_url="http://localhost:8080")
event_types = client.get_event_types()
```

**Go:**
```bash
go get github.com/yourusername/flowcatalyst-sdk-go
```

```go
import flowcatalyst "github.com/yourusername/flowcatalyst-sdk-go"

client := flowcatalyst.NewClient(flowcatalyst.Config{
    BaseURL: "http://localhost:8080",
})
eventTypes, _ := client.GetEventTypes()
```

### Using UI Components

```bash
npm install @flowcatalyst/ui-components
```

```vue
<script setup lang="ts">
import { Button, Card } from '@flowcatalyst/ui-components'
import '@flowcatalyst/ui-components/style.css'
</script>

<template>
  <Card variant="elevated">
    <template #header>My Application</template>
    <Button variant="primary">Action</Button>
  </Card>
</template>
```

## Development

### Backend Development

```bash
# Message router development (no database)
./gradlew :core:flowcatalyst-message-router:quarkusDev

# Core platform development
./gradlew :core:flowcatalyst-core:quarkusDev

# Full-stack development
./gradlew :core:flowcatalyst-app:quarkusDev
```

### Frontend Development

```bash
cd packages

# Install dependencies
bun install

# Develop UI components
bun run ui:dev

# Develop control plane app
bun run control-plane:dev

# Or run integrated with backend
./gradlew :core:flowcatalyst-control-plane-bffe:quarkusDev
```

### Client SDK Development

**TypeScript:**
```bash
cd clients/typescript/flowcatalyst-sdk
npm install
npm run dev
```

**Python:**
```bash
cd clients/python/flowcatalyst-sdk
pip install -e ".[dev]"
```

**Go:**
```bash
cd clients/go/flowcatalyst-sdk
go build
```

## Architecture

### Platform Modules (Core)

**Library Modules:**
- `flowcatalyst-message-router` - Stateless message routing (no database)
- `flowcatalyst-core` - Dispatch jobs, webhooks, database persistence
- `flowcatalyst-auth` - OIDC authentication and security

**Application Modules:**
- `flowcatalyst-router-app` - Router-only deployment (lightweight, stateless)
- `flowcatalyst-app` - Full-stack deployment (router + core)
- `flowcatalyst-control-plane-bffe` - Control plane backend + UI

### Publishing Model

FlowCatalyst components are published separately:

**Java Modules** → Maven Central
- `tech.flowcatalyst:flowcatalyst-core`
- `tech.flowcatalyst:flowcatalyst-message-router`
- `tech.flowcatalyst:flowcatalyst-auth`

**Client SDKs** → npm, PyPI, Go modules
- `@flowcatalyst/sdk` (npm)
- `flowcatalyst-sdk` (PyPI)
- `github.com/yourusername/flowcatalyst-sdk-go` (Go)

**UI Components** → npm
- `@flowcatalyst/ui-components`

**Platform Apps** → Docker Hub
- `flowcatalyst/control-plane`
- `flowcatalyst/message-router`

## Building Your Application

External applications integrate with FlowCatalyst by:

1. **Running the platform** - Deploy router and core services
2. **Using client SDKs** - Integrate with your language of choice
3. **Optional UI components** - Build management UIs with shared components

**Example: Building an Event-Driven App**
```bash
# 1. Run FlowCatalyst platform
docker run -p 8080:8080 flowcatalyst/message-router

# 2. Integrate with SDK (TypeScript example)
npm install @flowcatalyst/sdk

# 3. Build your app
# Your app connects to FlowCatalyst via SDK
```

## Documentation

- **[Developer Guide](DEVELOPER_GUIDE.md)** - Complete development guide
- **[Build Quick Reference](BUILD_QUICK_REFERENCE.md)** - Common build commands
- **[Architecture](docs/architecture.md)** - System architecture
- **[Testing Guide](docs/TESTING.md)** - Integration and unit testing

### Component-Specific Documentation
- **[UI Components](packages/ui-components/README.md)** - Vue component library
- **[Control Plane](packages/control-plane/README.md)** - Management UI
- **[TypeScript SDK](clients/typescript/flowcatalyst-sdk/README.md)**
- **[Python SDK](clients/python/flowcatalyst-sdk/README.md)**
- **[Go SDK](clients/go/flowcatalyst-sdk/README.md)**

## Community & Support

- **GitHub Issues** - Bug reports and feature requests
- **Discussions** - Questions and community support
- **Contributing** - See CONTRIBUTING.md

## License

Apache-2.0 - See LICENSE file for details

## About

FlowCatalyst is an open source platform for building event-driven, domain-oriented applications with reliable message processing and webhook delivery.
