# TypeScript BFFE Architecture Decision

## Executive Summary

This document outlines the architectural approach for building a **TypeScript Backend-For-Frontend (BFFE)** layer while maintaining the core business logic in **Java/Quarkus**. This hybrid approach provides:

- **Seamless full-stack TypeScript development** for application/UI teams
- **Proven Java platform** for complex business logic and high-performance message processing
- **Clean separation of concerns** between presentation and domain layers
- **Minimal migration risk** with incremental adoption path

**Status**: Proposed Architecture (Not Yet Implemented)
**Decision Date**: 2025-11-01
**Target Implementation**: Q1 2026

---

## Table of Contents

1. [Context and Problem Statement](#context-and-problem-statement)
2. [Current Architecture](#current-architecture)
3. [Proposed Architecture](#proposed-architecture)
4. [Architectural Principles](#architectural-principles)
5. [Technology Stack Comparison](#technology-stack-comparison)
6. [Implementation Details](#implementation-details)
7. [Migration Path](#migration-path)
8. [Developer Workflows](#developer-workflows)
9. [Deployment Options](#deployment-options)
10. [Benefits and Tradeoffs](#benefits-and-tradeoffs)
11. [Decision Rationale](#decision-rationale)
12. [References](#references)

---

## Context and Problem Statement

### The Challenge

FlowCatalyst is a high-performance event-driven platform with:
- **Backend**: Java 21 + Quarkus (92 classes, 6,500+ LOC)
- **Frontend**: Vue 3 + TypeScript (1,254+ TypeScript files)
- **Team Size**: 20 developers
- **Domain**: Logistics services with complex partner integrations

### Key Questions

1. Can we provide a **single-language development experience** (TypeScript) for application developers?
2. Should we migrate the entire backend to TypeScript, or adopt a hybrid approach?
3. How do we balance **developer productivity** with **technical excellence**?

### Constraints

- ✅ Must maintain high performance (10,000+ msg/sec)
- ✅ Must support complex integrations (EDI, SOAP, XML, message brokers)
- ✅ Must minimize risk to production-stable code
- ✅ Must enable 20-developer team to work efficiently

---

## Current Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────┐
│  BFFE (Java/Quarkus) - Port 8080                            │
│  - 2 basic endpoints (health, stats) - 41 LOC               │
│  - Serves Vue 3 frontend (via proxy in dev)                 │
│  - OIDC session management (configured, not enforced)       │
│  - Depends on: flowcatalyst-core, flowcatalyst-auth         │
└─────────────────────────────────────────────────────────────┘
                           │
                           │ (Direct Java calls)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Core Business Services (Java/Quarkus)                      │
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │ flowcatalyst-core (28 classes)                     │    │
│  │ - DispatchJobResource (REST API)                   │    │
│  │ - CredentialsAdminResource (REST API)              │    │
│  │ - Services: DispatchJobService, WebhookDispatcher  │    │
│  │ - Repositories: Hibernate ORM Panache              │    │
│  │ - Entities: DispatchJob, DispatchCredentials       │    │
│  └────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │ flowcatalyst-message-router (64 classes)           │    │
│  │ - Per-message-group virtual thread architecture    │    │
│  │ - SQS/ActiveMQ consumers (long polling)            │    │
│  │ - Circuit breaker, retry, rate limiting            │    │
│  │ - Prometheus metrics, structured logging           │    │
│  │ - Target: 10,000+ msg/sec throughput               │    │
│  └────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │ flowcatalyst-auth                                  │    │
│  │ - RBAC (Principal, Role, Permission)               │    │
│  │ - Tenant management                                │    │
│  │ - User/ServiceAccount models                       │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
                     PostgreSQL 16+
```

### Current State Assessment

| Aspect | Status | Notes |
|--------|--------|-------|
| **BFFE Complexity** | Minimal | Only 41 LOC, 2 endpoints |
| **Separation** | Excellent | Clean boundaries, independent builds |
| **Business Logic Location** | Core modules | Correctly placed in domain layer |
| **API Design** | Good | REST endpoints, proper DTOs |
| **Frontend Integration** | Skeleton | Proxy configured, APIs not implemented |
| **Migration Risk** | Low | BFFE is minimal, easy to replace |

**Key Finding**: The BFFE is essentially empty (41 lines of code), making it an ideal candidate for TypeScript migration with minimal risk.

---

## Proposed Architecture

### System Overview

```
┌─────────────────────────────────────────────────────────────┐
│  TypeScript BFFE (Bun + Hono) - Port 3000                   │
│  ┌────────────────────────────────────────────────────┐    │
│  │ Frontend Serving                                    │    │
│  │ - Vite-built Vue 3 static assets                   │    │
│  │ - SPA routing support (history mode)               │    │
│  │ - Cache headers, gzip compression                  │    │
│  └────────────────────────────────────────────────────┘    │
│  ┌────────────────────────────────────────────────────┐    │
│  │ API Gateway / Aggregation Layer                    │    │
│  │ - /api/health → health checks                      │    │
│  │ - /api/stats → aggregate from multiple services    │    │
│  │ - /api/dispatch-jobs → proxy to core service       │    │
│  │ - /api/dashboard → compose multiple service calls  │    │
│  │ - /api/credentials → proxy to core service         │    │
│  └────────────────────────────────────────────────────┘    │
│  ┌────────────────────────────────────────────────────┐    │
│  │ Session / Authentication Management                │    │
│  │ - OIDC session handling (passport.js)              │    │
│  │ - JWT validation and refresh                       │    │
│  │ - CSRF protection                                  │    │
│  │ - Cookie management (secure, httpOnly, sameSite)   │    │
│  └────────────────────────────────────────────────────┘    │
│  ┌────────────────────────────────────────────────────┐    │
│  │ Cross-Cutting Concerns                             │    │
│  │ - Request/response logging (pino)                  │    │
│  │ - Error handling and transformation                │    │
│  │ - Rate limiting (per-user, per-endpoint)           │    │
│  │ - Response caching (short-lived, in-memory)        │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                           │
                           │ HTTP/REST (Internal Network)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Core Business Services (Java/Quarkus) - UNCHANGED          │
│  - Internal ports only (8081, 8082, etc.)                   │
│  - Not exposed to internet directly                         │
│  - All business logic, database access, message routing     │
│                                                              │
│  ├─> flowcatalyst-core (port 8081)                          │
│  ├─> flowcatalyst-message-router (port 8082)                │
│  └─> flowcatalyst-auth (port 8083)                          │
└─────────────────────────────────────────────────────────────┘
```

### Network Architecture

```
Internet
   │
   ├─> Load Balancer (HTTPS)
   │      │
   │      └─> TypeScript BFFE (port 3000) ──┐
   │             - Public-facing             │
   │             - TLS termination           │
   │             - Session management        │
   │                                         │
   └─> Private Network ─────────────────────┘
          (Internal services, no direct internet access)
          │
          ├─> Java Core Service (port 8081)
          │      - Dispatch Jobs API
          │      - Credentials API
          │      - Webhook Dispatcher
          │
          ├─> Java Message Router (port 8082)
          │      - Queue consumers
          │      - Virtual thread pools
          │      - Message processing
          │
          ├─> Java Auth Service (port 8083)
          │      - User/ServiceAccount management
          │      - RBAC enforcement
          │
          └─> PostgreSQL (port 5432)
                 - Single database
                 - Accessed only by Java services
```

---

## Architectural Principles

### 1. Separation of Concerns

**TypeScript BFFE Responsibilities:**
- ✅ Frontend asset serving
- ✅ API gateway (routing, aggregation, composition)
- ✅ Session management (OIDC flow, JWT refresh)
- ✅ Request/response transformation for UI needs
- ✅ Public-facing rate limiting
- ✅ Short-term caching of aggregated data
- ❌ **NO business logic**
- ❌ **NO direct database access**
- ❌ **NO message queue access**

**Java Services Responsibilities:**
- ✅ All business logic (dispatch jobs, message routing)
- ✅ Database transactions and persistence
- ✅ Message queue processing (SQS, ActiveMQ)
- ✅ Partner integrations (EDI, SOAP, XML)
- ✅ Complex computations and algorithms
- ✅ Virtual thread architecture for high performance
- ❌ **NO frontend concerns**
- ❌ **NO direct internet exposure**

### 2. Technology Fit

**Use TypeScript/JavaScript For:**
- Simple API composition/aggregation
- Frontend serving and routing
- Session cookie management
- JSON transformation for UI
- Rapid prototyping of UI-facing endpoints

**Use Java For:**
- Complex business logic with strong typing
- High-performance message processing (virtual threads)
- Transactional database operations
- Integration with legacy systems (SOAP, EDI)
- Long-running background jobs
- Resource-intensive computations

### 3. Communication Patterns

**BFFE → Java Services:**
- Protocol: HTTP/REST (consideration: gRPC for performance)
- Network: Internal only (no public exposure)
- Authentication: Service-to-service auth (mTLS or JWT)
- Timeout: Short (2-5 seconds per call)
- Retry: Limited (fail fast, let UI retry)

**Frontend → BFFE:**
- Protocol: HTTPS (TLS 1.3)
- Authentication: Session cookies or JWT
- Caching: ETags, Cache-Control headers
- Compression: gzip/brotli

---

## Technology Stack Comparison

### TypeScript BFFE Stack

| Category | Technology | Rationale |
|----------|-----------|-----------|
| **Runtime** | Bun 1.x | Fast startup, native TypeScript, built-in bundler |
| **Web Framework** | Hono | Minimal, fast, Express-like API |
| **Validation** | Zod | Type-safe runtime validation |
| **Logging** | Pino | Structured JSON logging, high performance |
| **Testing** | Vitest | Already used in frontend, fast |
| **Type Checking** | TypeScript 5.x | Industry standard |

**Total Dependencies**: ~8 packages (minimal dependency footprint)

### Java Services Stack (Unchanged)

| Category | Technology | Rationale |
|----------|-----------|-----------|
| **Runtime** | Java 21 + Quarkus 3.x | Virtual threads, native compilation, integrated platform |
| **Web Framework** | Quarkus REST (RESTEasy Reactive) | High performance, non-blocking |
| **ORM** | Hibernate ORM Panache | Mature, feature-rich, active record pattern |
| **Validation** | Hibernate Validator | Bean validation standard |
| **Resilience** | SmallRye Fault Tolerance | Circuit breaker, retry, timeout |
| **Messaging** | AWS SQS, ActiveMQ | Production-proven, high throughput |
| **Logging** | Quarkus Logging (JSON) | Structured, integrated with Micrometer |
| **Metrics** | Micrometer + Prometheus | Industry standard observability |
| **Testing** | JUnit 5 + TestContainers | Comprehensive, integration test support |

**Total Dependencies**: ~20 Quarkus extensions (coordinated via BOM, single version upgrade)

### Dependency Management Comparison

**TypeScript BFFE** (package.json):
```json
{
  "dependencies": {
    "hono": "^4.0.0",
    "zod": "^3.22.0",
    "pino": "^9.0.0"
  },
  "devDependencies": {
    "@types/bun": "latest",
    "typescript": "^5.3.3"
  }
}
```
- 3 runtime dependencies
- Manual version management
- Weekly Dependabot PRs expected
- Breaking changes in patch versions (semver theater)

**Java Services** (build.gradle.kts):
```gradle
dependencies {
    implementation(enforcedPlatform("io.quarkus:quarkus-bom:3.28.2"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-hibernate-orm-panache")
    // ... 18 more extensions
}
```
- 20 extensions, all version-coordinated by BOM
- Quarterly Quarkus upgrades (tested together)
- Breaking changes rare, documented migration guides

**Verdict**: Java dependency management is significantly simpler for complex applications.

---

## Implementation Details

### Project Structure

```
flowcatalyst/
├── core/                              # Java services (EXISTING)
│   ├── flowcatalyst-core/
│   ├── flowcatalyst-message-router/
│   ├── flowcatalyst-auth/
│   └── flowcatalyst-postbox/
│
├── services/                          # NEW: TypeScript services
│   └── bffe/
│       ├── src/
│       │   ├── index.ts               # Main server entry point
│       │   ├── routes/                # API route handlers
│       │   │   ├── health.ts
│       │   │   ├── stats.ts
│       │   │   ├── dispatch-jobs.ts
│       │   │   ├── credentials.ts
│       │   │   └── dashboard.ts
│       │   ├── clients/               # Java service HTTP clients
│       │   │   ├── core-service.ts
│       │   │   ├── auth-service.ts
│       │   │   └── router-service.ts
│       │   ├── middleware/            # Middleware functions
│       │   │   ├── auth.ts
│       │   │   ├── cors.ts
│       │   │   ├── rate-limit.ts
│       │   │   └── error-handler.ts
│       │   ├── types/                 # Shared TypeScript types
│       │   │   ├── api.ts
│       │   │   └── domain.ts
│       │   └── utils/
│       │       ├── logger.ts
│       │       └── config.ts
│       ├── test/                      # Vitest tests
│       ├── public/                    # Vue build output (production)
│       ├── package.json
│       ├── tsconfig.json
│       └── README.md
│
└── packages/                          # Frontend (EXISTING)
    └── app/
        ├── src/
        ├── vite.config.ts             # Update proxy to port 3000
        └── package.json
```

### TypeScript BFFE Implementation

#### 1. Main Server (src/index.ts)

```typescript
import { Hono } from 'hono'
import { serve } from '@hono/node-server'
import { serveStatic } from '@hono/node-server/serve-static'
import { logger } from 'hono/logger'
import { cors } from 'hono/cors'
import pino from 'pino'

import { healthRoutes } from './routes/health'
import { statsRoutes } from './routes/stats'
import { dispatchJobsRoutes } from './routes/dispatch-jobs'
import { credentialsRoutes } from './routes/credentials'
import { dashboardRoutes } from './routes/dashboard'
import { authMiddleware } from './middleware/auth'
import { rateLimitMiddleware } from './middleware/rate-limit'
import { errorHandler } from './middleware/error-handler'

const log = pino({
  level: process.env.LOG_LEVEL || 'info',
  transport: process.env.NODE_ENV === 'development'
    ? { target: 'pino-pretty' }
    : undefined
})

const app = new Hono()

// Global middleware
app.use('*', logger())
app.use('*', errorHandler())

// CORS for API routes
app.use('/api/*', cors({
  origin: process.env.CORS_ORIGINS?.split(',') || ['http://localhost:5173'],
  credentials: true,
  allowHeaders: ['Content-Type', 'Authorization'],
  allowMethods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
}))

// Rate limiting for API routes
app.use('/api/*', rateLimitMiddleware())

// Public API routes (no auth required)
app.route('/api/health', healthRoutes)

// Protected API routes (auth required)
app.route('/api/stats', authMiddleware(statsRoutes))
app.route('/api/dispatch-jobs', authMiddleware(dispatchJobsRoutes))
app.route('/api/credentials', authMiddleware(credentialsRoutes))
app.route('/api/dashboard', authMiddleware(dashboardRoutes))

// Serve Vue frontend static assets (production only)
if (process.env.NODE_ENV === 'production') {
  app.use('/*', serveStatic({ root: './public' }))
  // SPA fallback - serve index.html for all non-API routes
  app.get('/*', serveStatic({ path: './public/index.html' }))
}

// Start server
const port = parseInt(process.env.PORT || '3000')
const host = process.env.HOST || '0.0.0.0'

serve({
  fetch: app.fetch,
  port,
  hostname: host,
})

log.info(`🚀 FlowCatalyst BFFE server running on http://${host}:${port}`)
log.info(`📊 Environment: ${process.env.NODE_ENV || 'development'}`)
log.info(`🔗 Core Service: ${process.env.CORE_SERVICE_URL || 'http://localhost:8081'}`)
```

#### 2. Java Service Client (src/clients/core-service.ts)

```typescript
import pino from 'pino'

const log = pino()

const CORE_SERVICE_URL = process.env.CORE_SERVICE_URL || 'http://localhost:8081'
const SERVICE_TIMEOUT = parseInt(process.env.SERVICE_TIMEOUT || '5000')

export interface DispatchJob {
  id: string
  externalId: string
  source: string
  type: string
  groupId?: string
  targetUrl: string
  protocol: string
  payload: string
  payloadContentType: string
  headers?: Record<string, string>
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'EXPIRED'
  maxRetries: number
  attemptCount: number
  scheduledFor?: string
  expiresAt?: string
  createdAt: string
  updatedAt: string
}

export interface CreateDispatchJobRequest {
  externalId: string
  source: string
  type: string
  groupId?: string
  targetUrl: string
  protocol?: string
  payload: string
  payloadContentType?: string
  headers?: Record<string, string>
  maxRetries?: number
  scheduledFor?: string
  expiresAt?: string
  idempotencyKey?: string
}

export interface DispatchAttempt {
  id: string
  jobId: string
  attemptNumber: number
  status: string
  responseCode?: number
  responseBody?: string
  errorMessage?: string
  errorStackTrace?: string
  attemptedAt: string
  completedAt: string
  durationMillis: number
}

/**
 * HTTP client for flowcatalyst-core service (Java/Quarkus)
 *
 * Handles:
 * - Dispatch job management
 * - Credentials management
 * - Webhook dispatch operations
 */
export class CoreServiceClient {
  private baseUrl: string
  private timeout: number

  constructor(baseUrl: string = CORE_SERVICE_URL, timeout: number = SERVICE_TIMEOUT) {
    this.baseUrl = baseUrl
    this.timeout = timeout
    log.info({ baseUrl, timeout }, 'CoreServiceClient initialized')
  }

  /**
   * Get a single dispatch job by ID
   */
  async getDispatchJob(id: string): Promise<DispatchJob> {
    const url = `${this.baseUrl}/dispatch-jobs/${id}`
    log.debug({ url, id }, 'Fetching dispatch job')

    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), this.timeout)

    try {
      const response = await fetch(url, {
        signal: controller.signal,
        headers: { 'Accept': 'application/json' }
      })
      clearTimeout(timeoutId)

      if (!response.ok) {
        const error = await response.text()
        log.error({ status: response.status, error, id }, 'Failed to fetch dispatch job')
        throw new Error(`Failed to fetch dispatch job: ${response.statusText}`)
      }

      const job = await response.json()
      log.debug({ id, status: job.status }, 'Dispatch job fetched successfully')
      return job
    } catch (error) {
      clearTimeout(timeoutId)
      if (error instanceof Error && error.name === 'AbortError') {
        log.error({ url, timeout: this.timeout }, 'Request timeout')
        throw new Error(`Request timeout after ${this.timeout}ms`)
      }
      throw error
    }
  }

  /**
   * List dispatch jobs with optional filtering
   */
  async listDispatchJobs(filters?: {
    status?: string
    source?: string
    type?: string
    groupId?: string
    externalId?: string
    limit?: number
    offset?: number
  }): Promise<DispatchJob[]> {
    const params = new URLSearchParams()
    if (filters?.status) params.set('status', filters.status)
    if (filters?.source) params.set('source', filters.source)
    if (filters?.type) params.set('type', filters.type)
    if (filters?.groupId) params.set('groupId', filters.groupId)
    if (filters?.externalId) params.set('externalId', filters.externalId)
    if (filters?.limit) params.set('limit', filters.limit.toString())
    if (filters?.offset) params.set('offset', filters.offset.toString())

    const url = `${this.baseUrl}/dispatch-jobs?${params}`
    log.debug({ url, filters }, 'Listing dispatch jobs')

    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), this.timeout)

    try {
      const response = await fetch(url, {
        signal: controller.signal,
        headers: { 'Accept': 'application/json' }
      })
      clearTimeout(timeoutId)

      if (!response.ok) {
        const error = await response.text()
        log.error({ status: response.status, error, filters }, 'Failed to list dispatch jobs')
        throw new Error(`Failed to list dispatch jobs: ${response.statusText}`)
      }

      const jobs = await response.json()
      log.debug({ count: jobs.length, filters }, 'Dispatch jobs listed successfully')
      return jobs
    } catch (error) {
      clearTimeout(timeoutId)
      if (error instanceof Error && error.name === 'AbortError') {
        log.error({ url, timeout: this.timeout }, 'Request timeout')
        throw new Error(`Request timeout after ${this.timeout}ms`)
      }
      throw error
    }
  }

  /**
   * Create a new dispatch job
   */
  async createDispatchJob(request: CreateDispatchJobRequest): Promise<DispatchJob> {
    const url = `${this.baseUrl}/dispatch-jobs`
    log.debug({ url, request }, 'Creating dispatch job')

    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), this.timeout)

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json'
        },
        body: JSON.stringify(request),
        signal: controller.signal,
      })
      clearTimeout(timeoutId)

      if (!response.ok) {
        const error = await response.text()
        log.error({ status: response.status, error, request }, 'Failed to create dispatch job')
        throw new Error(`Failed to create dispatch job: ${response.statusText}`)
      }

      const job = await response.json()
      log.info({ id: job.id, externalId: job.externalId }, 'Dispatch job created successfully')
      return job
    } catch (error) {
      clearTimeout(timeoutId)
      if (error instanceof Error && error.name === 'AbortError') {
        log.error({ url, timeout: this.timeout }, 'Request timeout')
        throw new Error(`Request timeout after ${this.timeout}ms`)
      }
      throw error
    }
  }

  /**
   * Get dispatch attempts for a job
   */
  async getDispatchAttempts(jobId: string): Promise<DispatchAttempt[]> {
    const url = `${this.baseUrl}/dispatch-jobs/${jobId}/attempts`
    log.debug({ url, jobId }, 'Fetching dispatch attempts')

    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), this.timeout)

    try {
      const response = await fetch(url, {
        signal: controller.signal,
        headers: { 'Accept': 'application/json' }
      })
      clearTimeout(timeoutId)

      if (!response.ok) {
        const error = await response.text()
        log.error({ status: response.status, error, jobId }, 'Failed to fetch dispatch attempts')
        throw new Error(`Failed to fetch dispatch attempts: ${response.statusText}`)
      }

      const attempts = await response.json()
      log.debug({ jobId, count: attempts.length }, 'Dispatch attempts fetched successfully')
      return attempts
    } catch (error) {
      clearTimeout(timeoutId)
      if (error instanceof Error && error.name === 'AbortError') {
        log.error({ url, timeout: this.timeout }, 'Request timeout')
        throw new Error(`Request timeout after ${this.timeout}ms`)
      }
      throw error
    }
  }
}

// Singleton instance
export const coreService = new CoreServiceClient()
```

#### 3. API Routes - Dispatch Jobs (src/routes/dispatch-jobs.ts)

```typescript
import { Hono } from 'hono'
import { zValidator } from '@hono/zod-validator'
import { z } from 'zod'
import { coreService } from '../clients/core-service'
import pino from 'pino'

const log = pino()

export const dispatchJobsRoutes = new Hono()

// Validation schemas
const createJobSchema = z.object({
  externalId: z.string().min(1).max(100),
  source: z.string().min(1).max(100),
  type: z.string().min(1).max(100),
  groupId: z.string().max(100).optional(),
  targetUrl: z.string().url().max(2048),
  protocol: z.enum(['HTTP_WEBHOOK']).default('HTTP_WEBHOOK'),
  payload: z.string(),
  payloadContentType: z.string().default('application/json'),
  headers: z.record(z.string()).optional(),
  maxRetries: z.number().int().min(0).max(10).default(3),
  scheduledFor: z.string().datetime().optional(),
  expiresAt: z.string().datetime().optional(),
  idempotencyKey: z.string().max(255).optional(),
})

const listJobsSchema = z.object({
  status: z.enum(['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED']).optional(),
  source: z.string().max(100).optional(),
  type: z.string().max(100).optional(),
  groupId: z.string().max(100).optional(),
  externalId: z.string().max(100).optional(),
  limit: z.coerce.number().int().min(1).max(1000).default(100),
  offset: z.coerce.number().int().min(0).default(0),
})

/**
 * List dispatch jobs with filtering
 * GET /api/dispatch-jobs?status=PENDING&source=orders&limit=50
 */
dispatchJobsRoutes.get('/', zValidator('query', listJobsSchema), async (c) => {
  const filters = c.req.valid('query')

  try {
    const jobs = await coreService.listDispatchJobs(filters)
    return c.json(jobs)
  } catch (error) {
    log.error({ error, filters }, 'Error listing dispatch jobs')
    return c.json({ error: 'Failed to list dispatch jobs' }, 500)
  }
})

/**
 * Get a single dispatch job by ID
 * GET /api/dispatch-jobs/:id
 */
dispatchJobsRoutes.get('/:id', async (c) => {
  const id = c.req.param('id')

  try {
    const job = await coreService.getDispatchJob(id)
    return c.json(job)
  } catch (error) {
    log.error({ error, id }, 'Error fetching dispatch job')
    return c.json({ error: 'Failed to fetch dispatch job' }, 500)
  }
})

/**
 * Create a new dispatch job
 * POST /api/dispatch-jobs
 */
dispatchJobsRoutes.post('/', zValidator('json', createJobSchema), async (c) => {
  const body = c.req.valid('json')

  try {
    const job = await coreService.createDispatchJob(body)
    log.info({ jobId: job.id, externalId: job.externalId }, 'Dispatch job created')
    return c.json(job, 201)
  } catch (error) {
    log.error({ error, request: body }, 'Error creating dispatch job')
    return c.json({ error: 'Failed to create dispatch job' }, 500)
  }
})

/**
 * Get dispatch attempts for a job
 * GET /api/dispatch-jobs/:id/attempts
 */
dispatchJobsRoutes.get('/:id/attempts', async (c) => {
  const id = c.req.param('id')

  try {
    const attempts = await coreService.getDispatchAttempts(id)
    return c.json(attempts)
  } catch (error) {
    log.error({ error, id }, 'Error fetching dispatch attempts')
    return c.json({ error: 'Failed to fetch dispatch attempts' }, 500)
  }
})
```

#### 4. API Routes - Dashboard (Aggregation Example) (src/routes/dashboard.ts)

```typescript
import { Hono } from 'hono'
import { coreService } from '../clients/core-service'
import pino from 'pino'

const log = pino()

export const dashboardRoutes = new Hono()

interface DashboardStats {
  totals: {
    pending: number
    processing: number
    completed: number
    failed: number
    expired: number
  }
  recentFailures: Array<{
    id: string
    source: string
    type: string
    failedAt: string
    errorMessage?: string
  }>
  sourceBreakdown: Record<string, number>
  typeBreakdown: Record<string, number>
  last24Hours: {
    completed: number
    failed: number
  }
}

/**
 * Dashboard endpoint - aggregates data from multiple Java service calls
 *
 * This demonstrates the value of the TypeScript BFFE:
 * - Parallel API calls to Java services
 * - Data aggregation and transformation
 * - UI-specific data structure
 *
 * GET /api/dashboard
 */
dashboardRoutes.get('/', async (c) => {
  try {
    // Call multiple Java services in parallel
    const [
      pendingJobs,
      processingJobs,
      completedJobs,
      failedJobs,
      expiredJobs,
    ] = await Promise.all([
      coreService.listDispatchJobs({ status: 'PENDING', limit: 100 }),
      coreService.listDispatchJobs({ status: 'PROCESSING', limit: 100 }),
      coreService.listDispatchJobs({ status: 'COMPLETED', limit: 100 }),
      coreService.listDispatchJobs({ status: 'FAILED', limit: 100 }),
      coreService.listDispatchJobs({ status: 'EXPIRED', limit: 100 }),
    ])

    // Aggregate data
    const allJobs = [
      ...pendingJobs,
      ...processingJobs,
      ...completedJobs,
      ...failedJobs,
      ...expiredJobs,
    ]

    // Calculate last 24 hours (filter by createdAt)
    const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString()
    const last24HoursJobs = allJobs.filter(job => job.createdAt >= yesterday)

    // Build dashboard stats
    const stats: DashboardStats = {
      totals: {
        pending: pendingJobs.length,
        processing: processingJobs.length,
        completed: completedJobs.length,
        failed: failedJobs.length,
        expired: expiredJobs.length,
      },
      recentFailures: failedJobs.slice(0, 10).map(job => ({
        id: job.id,
        source: job.source,
        type: job.type,
        failedAt: job.updatedAt,
        errorMessage: undefined, // Would fetch from attempts
      })),
      sourceBreakdown: calculateBreakdown(allJobs, 'source'),
      typeBreakdown: calculateBreakdown(allJobs, 'type'),
      last24Hours: {
        completed: last24HoursJobs.filter(j => j.status === 'COMPLETED').length,
        failed: last24HoursJobs.filter(j => j.status === 'FAILED').length,
      },
    }

    return c.json(stats)
  } catch (error) {
    log.error({ error }, 'Error fetching dashboard stats')
    return c.json({ error: 'Failed to fetch dashboard stats' }, 500)
  }
})

/**
 * Calculate breakdown by field (source or type)
 */
function calculateBreakdown<T extends { [key: string]: any }>(
  items: T[],
  field: keyof T
): Record<string, number> {
  const breakdown = new Map<string, number>()

  for (const item of items) {
    const value = String(item[field])
    breakdown.set(value, (breakdown.get(value) || 0) + 1)
  }

  return Object.fromEntries(breakdown)
}
```

#### 5. Environment Configuration

**services/bffe/.env.example**
```bash
# Server Configuration
NODE_ENV=development
PORT=3000
HOST=0.0.0.0
LOG_LEVEL=info

# CORS Configuration
CORS_ORIGINS=http://localhost:5173,http://localhost:3000

# Java Service URLs (internal network)
CORE_SERVICE_URL=http://localhost:8081
ROUTER_SERVICE_URL=http://localhost:8082
AUTH_SERVICE_URL=http://localhost:8083

# Service Request Timeout (milliseconds)
SERVICE_TIMEOUT=5000

# Session Configuration
SESSION_SECRET=change-me-in-production
SESSION_MAX_AGE=86400000

# OIDC Configuration (optional)
OIDC_ISSUER=https://auth.example.com
OIDC_CLIENT_ID=flowcatalyst-bffe
OIDC_CLIENT_SECRET=secret
OIDC_REDIRECT_URI=http://localhost:3000/auth/callback

# Rate Limiting
RATE_LIMIT_WINDOW_MS=60000
RATE_LIMIT_MAX_REQUESTS=100
```

---

## Migration Path

### Phase 1: Setup TypeScript BFFE (Week 1)

**Goals:**
- Create TypeScript BFFE project structure
- Implement basic health and stats endpoints
- Verify end-to-end flow with Java services

**Tasks:**
1. ✅ Create `services/bffe` directory structure
2. ✅ Initialize Bun project with TypeScript
3. ✅ Install dependencies (Hono, Zod, Pino)
4. ✅ Implement health endpoint
5. ✅ Implement stats endpoint
6. ✅ Create CoreServiceClient with basic methods
7. ✅ Add environment configuration
8. ✅ Write unit tests
9. ✅ Test locally alongside Java BFFE

**Success Criteria:**
- TypeScript BFFE runs on port 3000
- Health check returns 200 OK
- Stats endpoint calls Java service successfully
- All tests pass

### Phase 2: Implement Core Routes (Week 2)

**Goals:**
- Implement dispatch-jobs API routes
- Implement credentials API routes
- Implement dashboard aggregation endpoint

**Tasks:**
1. ✅ Implement dispatch-jobs routes (list, get, create)
2. ✅ Implement credentials routes (create, get, delete)
3. ✅ Implement dashboard aggregation endpoint
4. ✅ Add request validation (Zod schemas)
5. ✅ Add error handling middleware
6. ✅ Add logging middleware
7. ✅ Write integration tests
8. ✅ Update frontend Vite proxy to port 3000
9. ✅ Test end-to-end with Vue frontend

**Success Criteria:**
- All API routes functional
- Frontend can call BFFE successfully
- Validation catches invalid requests
- Errors logged with context

### Phase 3: Authentication & Session Management (Week 3)

**Goals:**
- Implement OIDC authentication flow
- Add session management
- Secure protected routes

**Tasks:**
1. ✅ Implement OIDC authentication middleware
2. ✅ Add session cookie management
3. ✅ Implement JWT validation
4. ✅ Add CSRF protection
5. ✅ Secure protected routes (dispatch-jobs, credentials)
6. ✅ Add rate limiting middleware
7. ✅ Test authentication flow
8. ✅ Update frontend to handle auth

**Success Criteria:**
- OIDC flow works end-to-end
- Protected routes require authentication
- Sessions persist correctly
- CSRF protection active

### Phase 4: Production Preparation (Week 4)

**Goals:**
- Production build configuration
- Docker containerization
- CI/CD pipeline updates
- Documentation

**Tasks:**
1. ✅ Configure production build (Bun build)
2. ✅ Add static asset serving for Vue frontend
3. ✅ Create Dockerfile for BFFE
4. ✅ Update docker-compose for local development
5. ✅ Add health checks for Kubernetes
6. ✅ Configure logging for production
7. ✅ Add metrics/observability
8. ✅ Update CI/CD pipeline (GitHub Actions / Jenkins)
9. ✅ Write deployment documentation
10. ✅ Load testing

**Success Criteria:**
- Production build succeeds
- Docker container runs successfully
- Health checks work in Kubernetes
- CI/CD pipeline green
- Documentation complete

### Phase 5: Deployment & Monitoring (Week 5)

**Goals:**
- Deploy to staging environment
- Monitor and validate
- Deploy to production

**Tasks:**
1. ✅ Deploy to staging
2. ✅ Run smoke tests
3. ✅ Monitor metrics (response times, error rates)
4. ✅ Load testing in staging
5. ✅ Security scan (OWASP, dependency check)
6. ✅ Deploy to production (canary or blue-green)
7. ✅ Monitor production metrics
8. ✅ Decommission Java BFFE (after validation period)

**Success Criteria:**
- Staging deployment successful
- All tests pass in staging
- Production deployment successful
- No increase in error rates
- Response times acceptable

---

## Developer Workflows

### Full Stack TypeScript Development

**Scenario**: UI developer working on new dashboard feature

```bash
# Terminal 1: Start Java services (background)
# These run on ports 8081, 8082, 8083
docker-compose up -d postgres localstack
./gradlew :core:flowcatalyst-core:quarkusDev &
./gradlew :core:flowcatalyst-message-router:quarkusDev &

# Terminal 2: Start TypeScript BFFE
cd services/bffe
bun install
bun dev
# 🚀 BFFE running on http://localhost:3000
# 🔄 Watch mode enabled (hot reload)

# Terminal 3: Start Vue frontend
cd packages/app
bun dev
# ⚡️ Vite running on http://localhost:5173
# 🔄 Hot module replacement enabled

# Open browser: http://localhost:5173
# Edit Vue components → Hot reload
# Edit BFFE routes → Auto restart
# All TypeScript, seamless development
```

**What the developer sees:**
- ✅ Full TypeScript stack (frontend + BFFE)
- ✅ Hot reload works across both
- ✅ Single language, consistent patterns
- ✅ Fast feedback loop
- ✅ Type safety end-to-end

### Backend Java Development

**Scenario**: Backend developer working on new dispatch job feature

```bash
# Terminal 1: Start dependencies
docker-compose up postgres localstack

# Terminal 2: Start core service in dev mode
cd core/flowcatalyst-core
../../gradlew quarkusDev
# 🚀 Running on http://localhost:8081
# 🔄 Live reload enabled (continuous testing)

# Edit Java code → Automatic recompilation
# Add new endpoint → Available immediately
# Run tests → Fast feedback

# Test with curl or Postman
curl http://localhost:8081/dispatch-jobs
```

**What the developer sees:**
- ✅ Java services isolated
- ✅ Quarkus dev mode (fast reload)
- ✅ Direct API testing
- ✅ No need to run frontend stack

### Frontend-Only Development

**Scenario**: UI developer working on styling/components only

```bash
# Use mock API server (optional)
cd services/bffe
bun dev

cd packages/app
bun dev

# Or mock Java services entirely with MSW (Mock Service Worker)
# No need to run Java services at all
```

**What the developer sees:**
- ✅ No Java environment needed
- ✅ Full TypeScript stack
- ✅ Fast iteration on UI

---

## Deployment Options

### Option 1: Monolith (Single Container)

**Use Case**: Small deployments, single region, cost optimization

```dockerfile
# Multi-stage build
FROM oven/bun:1 AS bffe-builder
WORKDIR /app/bffe
COPY services/bffe/package.json services/bffe/bun.lockb ./
RUN bun install --frozen-lockfile --production
COPY services/bffe ./
RUN bun build src/index.ts --outdir=dist --target=bun

FROM amazoncorretto:21-alpine AS java-builder
WORKDIR /app
COPY core/ ./core
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN ./gradlew build -x test

# Final runtime image
FROM oven/bun:1
WORKDIR /app

# Install Java runtime (for running Java services)
RUN apt-get update && apt-get install -y openjdk-21-jre-headless && rm -rf /var/lib/apt/lists/*

# Copy BFFE
COPY --from=bffe-builder /app/bffe/dist /app/bffe
COPY --from=bffe-builder /app/bffe/node_modules /app/bffe/node_modules

# Copy Java services
COPY --from=java-builder /app/core/flowcatalyst-core/build/quarkus-app /app/java/core
COPY --from=java-builder /app/core/flowcatalyst-message-router/build/quarkus-app /app/java/router

# Copy startup script
COPY deploy/start-all.sh /app/
RUN chmod +x /app/start-all.sh

EXPOSE 3000 8081 8082

CMD ["/app/start-all.sh"]
```

**deploy/start-all.sh**:
```bash
#!/bin/bash
set -e

# Start Java services in background
java -jar /app/java/core/quarkus-run.jar &
java -jar /app/java/router/quarkus-run.jar &

# Wait for Java services to be ready
until curl -f http://localhost:8081/q/health; do
  echo "Waiting for core service..."
  sleep 2
done

until curl -f http://localhost:8082/q/health; do
  echo "Waiting for router service..."
  sleep 2
done

# Start BFFE (foreground)
cd /app/bffe
exec bun dist/index.js
```

### Option 2: Microservices (Separate Containers)

**Use Case**: Production deployments, horizontal scaling, service isolation

**docker-compose.yml** (Production):
```yaml
version: '3.8'

services:
  bffe:
    build: ./services/bffe
    ports:
      - "3000:3000"
    environment:
      NODE_ENV: production
      CORE_SERVICE_URL: http://core:8081
      ROUTER_SERVICE_URL: http://router:8082
      AUTH_SERVICE_URL: http://auth:8083
    depends_on:
      - core
      - router
      - auth
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3000/api/health"]
      interval: 30s
      timeout: 3s
      retries: 3

  core:
    build: ./core/flowcatalyst-core
    ports:
      - "8081:8081"  # Internal only (not exposed externally)
    environment:
      QUARKUS_HTTP_PORT: 8081
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://postgres:5432/flowcatalyst
      QUARKUS_DATASOURCE_USERNAME: flowcatalyst
      QUARKUS_DATASOURCE_PASSWORD: ${DB_PASSWORD}
    depends_on:
      - postgres
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/q/health"]
      interval: 30s
      timeout: 3s
      retries: 3

  router:
    build: ./core/flowcatalyst-message-router
    ports:
      - "8082:8082"  # Internal only
    environment:
      QUARKUS_HTTP_PORT: 8082
      MESSAGE_ROUTER_CONFIG_URL: http://core:8081/api/config
      SQS_ENDPOINT_OVERRIDE: ${SQS_ENDPOINT}
      AWS_REGION: ${AWS_REGION}
      AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID}
      AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}
    depends_on:
      - core
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8082/q/health"]
      interval: 30s
      timeout: 3s
      retries: 3

  auth:
    build: ./core/flowcatalyst-auth
    ports:
      - "8083:8083"  # Internal only
    environment:
      QUARKUS_HTTP_PORT: 8083
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://postgres:5432/flowcatalyst
      QUARKUS_OIDC_AUTH_SERVER_URL: ${OIDC_ISSUER}
    depends_on:
      - postgres

  postgres:
    image: postgres:16-alpine
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: flowcatalyst
      POSTGRES_USER: flowcatalyst
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```

**Kubernetes Deployment** (k8s/bffe-deployment.yaml):
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: flowcatalyst-bffe
  labels:
    app: flowcatalyst
    component: bffe
spec:
  replicas: 3
  selector:
    matchLabels:
      app: flowcatalyst
      component: bffe
  template:
    metadata:
      labels:
        app: flowcatalyst
        component: bffe
    spec:
      containers:
      - name: bffe
        image: flowcatalyst/bffe:latest
        ports:
        - containerPort: 3000
          name: http
        env:
        - name: NODE_ENV
          value: "production"
        - name: CORE_SERVICE_URL
          value: "http://flowcatalyst-core:8081"
        - name: ROUTER_SERVICE_URL
          value: "http://flowcatalyst-router:8082"
        - name: AUTH_SERVICE_URL
          value: "http://flowcatalyst-auth:8083"
        resources:
          requests:
            memory: "256Mi"
            cpu: "200m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /api/health
            port: 3000
          initialDelaySeconds: 10
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /api/health
            port: 3000
          initialDelaySeconds: 5
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: flowcatalyst-bffe
spec:
  type: LoadBalancer
  ports:
  - port: 80
    targetPort: 3000
    protocol: TCP
  selector:
    app: flowcatalyst
    component: bffe
```

### Option 3: Serverless (AWS Lambda)

**Use Case**: Variable traffic, cost optimization, auto-scaling

**services/bffe/lambda.ts** (Lambda handler):
```typescript
import { Hono } from 'hono'
import { handle } from 'hono/aws-lambda'
import { app } from './src/index'  // Import your Hono app

export const handler = handle(app)
```

**Deploy with AWS SAM or Serverless Framework**

---

## Benefits and Tradeoffs

### Benefits

#### For Development Teams

| Benefit | Impact | Stakeholder |
|---------|--------|-------------|
| **Single Language** | TypeScript across frontend + BFFE = easier onboarding, knowledge sharing | Frontend team (8 devs) |
| **Fast Iteration** | Bun dev mode + hot reload = rapid prototyping | All developers |
| **Type Safety** | Shared types between frontend and BFFE = fewer runtime errors | Frontend team |
| **Simplified Stack** | Only 8 npm packages for BFFE vs 20 Java extensions | DevOps / Maintenance |
| **Modern DX** | Better IDE support, faster feedback loops for UI work | Frontend team |

#### For Architecture

| Benefit | Impact |
|---------|--------|
| **Clean Separation** | BFFE layer isolates frontend from backend changes |
| **Independent Scaling** | Scale BFFE (CPU-light) separately from Java services (CPU-heavy) |
| **Security** | Java services not exposed to internet, only BFFE |
| **Flexibility** | Can swap BFFE implementation without touching business logic |
| **Polyglot** | Use best tool for each layer (TypeScript for UI, Java for business logic) |

#### For Business

| Benefit | Impact |
|---------|--------|
| **Faster Feature Delivery** | Frontend team can work without Java environment |
| **Lower Hiring Barrier** | Easier to hire TypeScript devs than full-stack Java/TypeScript |
| **Reduced Risk** | Keep proven Java services untouched |
| **Cost Optimization** | BFFE can run serverless (pay per request) |

### Tradeoffs

#### Increased Complexity

| Tradeoff | Mitigation |
|----------|------------|
| **Two Runtimes** | Standardize on Docker/Kubernetes for deployment |
| **Service Communication** | Add monitoring, tracing (OpenTelemetry) |
| **Network Latency** | Keep BFFE and Java services co-located |
| **Debugging** | Add distributed tracing (Jaeger, Zipkin) |

#### Operational Overhead

| Tradeoff | Mitigation |
|----------|------------|
| **Two Dependency Stacks** | Automate dependency updates (Dependabot, Renovate) |
| **Two Build Pipelines** | Use monorepo tooling (Turborepo, Nx) |
| **Two Monitoring Stacks** | Unified observability platform (Datadog, New Relic) |

#### Performance

| Tradeoff | Impact | Mitigation |
|----------|--------|------------|
| **Extra HTTP Hop** | +5-20ms latency per request | Keep services co-located, use HTTP/2, add caching |
| **No Direct DB Access** | BFFE must call Java APIs | Batch requests, add aggregation endpoints |
| **JSON Serialization** | Serialization overhead | Use HTTP/2, compression (gzip/brotli) |

### When NOT to Use This Architecture

❌ **Don't use if:**
- You have <5 developers (overhead not worth it)
- Your team is 100% Java (no TypeScript expertise)
- You need <10ms response times (extra hop adds latency)
- Your frontend is server-rendered (Next.js, Nuxt) - use framework's BFF instead
- You're building a monolithic enterprise app (not microservices)

---

## Decision Rationale

### Why TypeScript BFFE?

1. **Proven Separation**: The current BFFE is only 41 lines of code - clearly separated, low risk to replace
2. **Team Expertise**: Frontend team already uses TypeScript (1,254 files), natural extension
3. **Minimal Dependencies**: BFFE needs only 8 packages vs 20+ for equivalent Java functionality
4. **Right Tool for Job**: TypeScript excels at API composition, JSON transformation, session management
5. **Independent Evolution**: BFFE and Java services can evolve independently

### Why Keep Java Services?

1. **Technical Excellence**: Virtual thread architecture, circuit breakers, rate limiting - proven and mature
2. **Performance**: 10,000+ msg/sec throughput requirement needs Java's efficiency
3. **Ecosystem Maturity**: Quarkus platform, Hibernate ORM, AWS SDK - all battle-tested
4. **Integration Capabilities**: EDI, SOAP, XML - Java excels here
5. **Zero Risk**: Don't rewrite what works - "if it ain't broke, don't fix it"

### Why Hybrid Architecture?

**Best of Both Worlds:**
- ✅ TypeScript for UI-facing layer (fast iteration, modern DX)
- ✅ Java for business logic (performance, reliability, maturity)
- ✅ Clean separation enables team specialization
- ✅ Each layer uses appropriate technology
- ✅ Incremental adoption path (low risk)

**Alternatives Considered:**

| Alternative | Verdict | Reason |
|-------------|---------|--------|
| **Full TypeScript** | ❌ Rejected | Would require rewriting 6,500 LOC, high risk, performance regression likely |
| **Full Java** | ❌ Rejected | Forces frontend team to learn Java, slower iteration on UI features |
| **GraphQL Layer** | ⚠️ Overkill | Adds complexity without clear benefit for this use case |
| **API Gateway (Kong, etc.)** | ⚠️ Heavy | More infrastructure, less flexibility than custom BFFE |

---

## References

### Documentation

- [Current Architecture](./architecture.md)
- [Message Router Documentation](./message-router.md)
- [Dispatch Jobs Documentation](./dispatch-jobs.md)
- [Virtual Thread FIFO Architecture](./MESSAGE_GROUP_FIFO.md)

### Technology Documentation

**TypeScript BFFE:**
- [Bun Runtime](https://bun.sh/)
- [Hono Web Framework](https://hono.dev/)
- [Zod Validation](https://zod.dev/)
- [Pino Logging](https://getpino.io/)

**Java Services:**
- [Quarkus Documentation](https://quarkus.io/guides/)
- [Hibernate ORM Panache](https://quarkus.io/guides/hibernate-orm-panache)
- [SmallRye Fault Tolerance](https://smallrye.io/docs/smallrye-fault-tolerance/6.2.6/index.html)

### Code Examples

- **BFFE Implementation**: `services/bffe/` (to be created)
- **Core Services**: `core/flowcatalyst-core/`
- **Message Router**: `core/flowcatalyst-message-router/`
- **Frontend**: `packages/app/`

### Performance Benchmarks

(To be added after implementation)

- BFFE response time metrics
- Service-to-service latency
- End-to-end request latency
- Throughput comparison (before/after)

---

## Appendix A: Cost Analysis

### Infrastructure Costs (Monthly, AWS)

**Current (Java BFFE + Java Services):**
```
ECS Fargate (2 tasks × 1 vCPU, 2GB RAM):     $60/month
ALB (Application Load Balancer):             $25/month
RDS PostgreSQL (db.t3.medium):               $80/month
SQS (10M requests):                          $5/month
CloudWatch Logs:                             $10/month
Total:                                       $180/month
```

**Proposed (TypeScript BFFE + Java Services):**
```
BFFE - Lambda (1M requests, 256MB, 500ms):   $5/month (serverless option)
  OR ECS Fargate (1 task × 0.5 vCPU, 1GB):   $25/month (container option)
Java Services (2 tasks × 1 vCPU, 2GB):       $60/month
ALB:                                         $25/month
RDS PostgreSQL:                              $80/month
SQS:                                         $5/month
CloudWatch Logs:                             $10/month
Total (serverless):                          $185/month (+2.7%)
Total (container):                           $205/month (+13.9%)
```

**Analysis**: Marginal cost increase, offset by improved developer productivity.

---

## Appendix B: Migration Checklist

### Pre-Migration

- [ ] Review this document with team
- [ ] Get stakeholder approval
- [ ] Allocate developer resources (1 senior TypeScript dev, 4 weeks)
- [ ] Set up development environment

### Phase 1: Setup (Week 1)

- [ ] Create `services/bffe` directory
- [ ] Initialize Bun project
- [ ] Install dependencies
- [ ] Implement health endpoint
- [ ] Implement stats endpoint
- [ ] Create CoreServiceClient
- [ ] Write unit tests
- [ ] Test locally

### Phase 2: Core Routes (Week 2)

- [ ] Implement dispatch-jobs routes
- [ ] Implement credentials routes
- [ ] Implement dashboard endpoint
- [ ] Add validation (Zod)
- [ ] Add error handling
- [ ] Add logging
- [ ] Write integration tests
- [ ] Update frontend proxy
- [ ] End-to-end testing

### Phase 3: Authentication (Week 3)

- [ ] Implement OIDC middleware
- [ ] Add session management
- [ ] Add JWT validation
- [ ] Add CSRF protection
- [ ] Secure protected routes
- [ ] Add rate limiting
- [ ] Test auth flow
- [ ] Update frontend auth

### Phase 4: Production Prep (Week 4)

- [ ] Configure production build
- [ ] Add static asset serving
- [ ] Create Dockerfile
- [ ] Update docker-compose
- [ ] Add health checks
- [ ] Configure logging
- [ ] Add metrics
- [ ] Update CI/CD
- [ ] Write deployment docs
- [ ] Load testing

### Phase 5: Deployment (Week 5)

- [ ] Deploy to staging
- [ ] Smoke tests
- [ ] Monitor metrics
- [ ] Load testing
- [ ] Security scan
- [ ] Deploy to production (canary)
- [ ] Monitor production
- [ ] Decommission Java BFFE

### Post-Migration

- [ ] Retrospective meeting
- [ ] Update documentation
- [ ] Knowledge transfer
- [ ] Monitor for 2 weeks
- [ ] Declare success ✅

---

**Document Version**: 1.0
**Last Updated**: 2025-11-01
**Status**: Proposed (Awaiting Approval)
**Next Review**: After Phase 1 completion

---

## Questions or Feedback?

Contact the architecture team or open a discussion in the team Slack channel.
