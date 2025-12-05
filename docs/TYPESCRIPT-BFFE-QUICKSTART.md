# TypeScript BFFE Quick Start Guide

**Related Document**: [TYPESCRIPT-BFFE-ARCHITECTURE.md](./TYPESCRIPT-BFFE-ARCHITECTURE.md)

This is a practical, hands-on guide for developers implementing the TypeScript BFFE layer.

---

## Quick Setup (5 Minutes)

### 1. Create Project Structure

```bash
cd /path/to/flowcatalyst

# Create BFFE directory
mkdir -p services/bffe/src/{routes,clients,middleware,types,utils}
mkdir -p services/bffe/test
mkdir -p services/bffe/public

cd services/bffe
```

### 2. Initialize Bun Project

```bash
bun init -y

# Install dependencies
bun add hono @hono/node-server zod pino

# Install dev dependencies
bun add -d @types/bun typescript
```

### 3. Create Basic Files

**package.json** (update scripts):
```json
{
  "name": "@flowcatalyst/bffe",
  "type": "module",
  "scripts": {
    "dev": "bun --watch src/index.ts",
    "build": "bun build src/index.ts --outdir=dist --target=bun",
    "start": "NODE_ENV=production bun dist/index.js",
    "test": "bun test",
    "lint": "tsc --noEmit"
  }
}
```

**tsconfig.json**:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "lib": ["ES2022"],
    "moduleResolution": "bundler",
    "types": ["bun-types"],
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "outDir": "./dist",
    "rootDir": "./src"
  },
  "include": ["src/**/*"],
  "exclude": ["node_modules", "dist"]
}
```

**.env**:
```bash
NODE_ENV=development
PORT=3000
LOG_LEVEL=info

# Java service URLs
CORE_SERVICE_URL=http://localhost:8081
ROUTER_SERVICE_URL=http://localhost:8082

# CORS
CORS_ORIGINS=http://localhost:5173
```

### 4. Create Minimal Server

**src/index.ts**:
```typescript
import { Hono } from 'hono'
import { serve } from '@hono/node-server'
import { logger } from 'hono/logger'
import { cors } from 'hono/cors'

const app = new Hono()

app.use('*', logger())
app.use('/api/*', cors({
  origin: 'http://localhost:5173',
  credentials: true,
}))

app.get('/api/health', (c) => {
  return c.json({ status: 'ok', timestamp: new Date().toISOString() })
})

const port = parseInt(process.env.PORT || '3000')
serve({ fetch: app.fetch, port })
console.log(`🚀 BFFE running on http://localhost:${port}`)
```

### 5. Test It

```bash
# Start BFFE
bun dev

# In another terminal, test
curl http://localhost:3000/api/health
# Expected: {"status":"ok","timestamp":"2025-11-01T..."}
```

---

## Adding Java Service Client (10 Minutes)

### 1. Create Client File

**src/clients/core-service.ts**:
```typescript
const CORE_SERVICE_URL = process.env.CORE_SERVICE_URL || 'http://localhost:8081'

export interface DispatchJob {
  id: string
  externalId: string
  source: string
  type: string
  status: string
  createdAt: string
}

export class CoreServiceClient {
  private baseUrl: string

  constructor(baseUrl = CORE_SERVICE_URL) {
    this.baseUrl = baseUrl
  }

  async listDispatchJobs(filters?: {
    status?: string
    limit?: number
  }): Promise<DispatchJob[]> {
    const params = new URLSearchParams()
    if (filters?.status) params.set('status', filters.status)
    if (filters?.limit) params.set('limit', filters.limit.toString())

    const response = await fetch(`${this.baseUrl}/dispatch-jobs?${params}`)
    if (!response.ok) {
      throw new Error(`Failed to list jobs: ${response.statusText}`)
    }
    return response.json()
  }

  async getDispatchJob(id: string): Promise<DispatchJob> {
    const response = await fetch(`${this.baseUrl}/dispatch-jobs/${id}`)
    if (!response.ok) {
      throw new Error(`Failed to get job: ${response.statusText}`)
    }
    return response.json()
  }
}

export const coreService = new CoreServiceClient()
```

### 2. Create Route

**src/routes/dispatch-jobs.ts**:
```typescript
import { Hono } from 'hono'
import { coreService } from '../clients/core-service'

export const dispatchJobsRoutes = new Hono()

dispatchJobsRoutes.get('/', async (c) => {
  const status = c.req.query('status')
  const limit = c.req.query('limit')

  const jobs = await coreService.listDispatchJobs({
    status,
    limit: limit ? parseInt(limit) : undefined,
  })

  return c.json(jobs)
})

dispatchJobsRoutes.get('/:id', async (c) => {
  const id = c.req.param('id')
  const job = await coreService.getDispatchJob(id)
  return c.json(job)
})
```

### 3. Wire It Up

**src/index.ts** (update):
```typescript
import { Hono } from 'hono'
import { serve } from '@hono/node-server'
import { logger } from 'hono/logger'
import { cors } from 'hono/cors'
import { dispatchJobsRoutes } from './routes/dispatch-jobs'  // ADD THIS

const app = new Hono()

app.use('*', logger())
app.use('/api/*', cors({
  origin: 'http://localhost:5173',
  credentials: true,
}))

app.get('/api/health', (c) => {
  return c.json({ status: 'ok', timestamp: new Date().toISOString() })
})

app.route('/api/dispatch-jobs', dispatchJobsRoutes)  // ADD THIS

const port = parseInt(process.env.PORT || '3000')
serve({ fetch: app.fetch, port })
console.log(`🚀 BFFE running on http://localhost:${port}`)
```

### 4. Test End-to-End

```bash
# Terminal 1: Start Java core service
cd core/flowcatalyst-core
../../gradlew quarkusDev

# Terminal 2: Start BFFE
cd services/bffe
bun dev

# Terminal 3: Test
curl http://localhost:3000/api/dispatch-jobs
# Should return jobs from Java service
```

---

## Update Frontend (5 Minutes)

### 1. Update Vite Proxy

**packages/app/vite.config.ts**:
```typescript
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:3000',  // Change from 8080 to 3000
        changeOrigin: true,
      },
    },
  },
  // ... rest unchanged
})
```

### 2. Test Frontend

```bash
# Terminal 1: BFFE
cd services/bffe && bun dev

# Terminal 2: Frontend
cd packages/app && bun dev

# Open browser: http://localhost:5173
# API calls now go through TypeScript BFFE
```

---

## Common Patterns

### Pattern 1: Simple Proxy (Pass-Through)

When you just need to forward requests:

```typescript
// src/routes/credentials.ts
import { Hono } from 'hono'
import { coreService } from '../clients/core-service'

export const credentialsRoutes = new Hono()

// List - just proxy
credentialsRoutes.get('/', async (c) => {
  const credentials = await coreService.listCredentials()
  return c.json(credentials)
})

// Get - just proxy
credentialsRoutes.get('/:id', async (c) => {
  const id = c.req.param('id')
  const credential = await coreService.getCredential(id)
  return c.json(credential)
})
```

### Pattern 2: Aggregation (Multiple Calls)

When you need to combine data from multiple services:

```typescript
// src/routes/dashboard.ts
import { Hono } from 'hono'
import { coreService } from '../clients/core-service'

export const dashboardRoutes = new Hono()

dashboardRoutes.get('/', async (c) => {
  // Call multiple services in parallel
  const [pending, completed, failed] = await Promise.all([
    coreService.listDispatchJobs({ status: 'PENDING' }),
    coreService.listDispatchJobs({ status: 'COMPLETED' }),
    coreService.listDispatchJobs({ status: 'FAILED' }),
  ])

  // Aggregate
  return c.json({
    totalPending: pending.length,
    totalCompleted: completed.length,
    totalFailed: failed.length,
    recentFailures: failed.slice(0, 10),
  })
})
```

### Pattern 3: Transformation (UI-Specific Format)

When you need to reshape data for the UI:

```typescript
// src/routes/stats.ts
import { Hono } from 'hono'
import { coreService } from '../clients/core-service'

export const statsRoutes = new Hono()

statsRoutes.get('/', async (c) => {
  const jobs = await coreService.listDispatchJobs({ limit: 1000 })

  // Transform for UI charts
  const bySource = jobs.reduce((acc, job) => {
    acc[job.source] = (acc[job.source] || 0) + 1
    return acc
  }, {} as Record<string, number>)

  const byStatus = jobs.reduce((acc, job) => {
    acc[job.status] = (acc[job.status] || 0) + 1
    return acc
  }, {} as Record<string, number>)

  return c.json({
    total: jobs.length,
    bySource,
    byStatus,
    timestamp: new Date().toISOString(),
  })
})
```

### Pattern 4: Validation (Request Body)

Using Zod for validation:

```typescript
import { Hono } from 'hono'
import { zValidator } from '@hono/zod-validator'
import { z } from 'zod'
import { coreService } from '../clients/core-service'

const createJobSchema = z.object({
  externalId: z.string().min(1),
  source: z.string().min(1),
  type: z.string().min(1),
  targetUrl: z.string().url(),
  payload: z.string(),
})

export const dispatchJobsRoutes = new Hono()

dispatchJobsRoutes.post('/',
  zValidator('json', createJobSchema),
  async (c) => {
    const body = c.req.valid('json')
    const job = await coreService.createDispatchJob(body)
    return c.json(job, 201)
  }
)
```

### Pattern 5: Error Handling

```typescript
// src/middleware/error-handler.ts
import { Hono } from 'hono'
import pino from 'pino'

const log = pino()

export function errorHandler() {
  return async (c: any, next: any) => {
    try {
      await next()
    } catch (error) {
      log.error({ error, path: c.req.path }, 'Request failed')

      if (error instanceof Error) {
        return c.json({
          error: error.message,
          path: c.req.path,
          timestamp: new Date().toISOString(),
        }, 500)
      }

      return c.json({ error: 'Internal server error' }, 500)
    }
  }
}
```

---

## Development Workflow

### Daily Development

```bash
# Start all services (use tmux or separate terminals)
./scripts/dev.sh  # Create this script

# Or manually:
# Terminal 1: Java services
docker-compose up -d postgres localstack
./gradlew :core:flowcatalyst-core:quarkusDev

# Terminal 2: BFFE
cd services/bffe && bun dev

# Terminal 3: Frontend
cd packages/app && bun dev
```

**Create scripts/dev.sh**:
```bash
#!/bin/bash
set -e

echo "Starting FlowCatalyst development environment..."

# Start dependencies
docker-compose up -d postgres localstack

# Start Java services in background
./gradlew :core:flowcatalyst-core:quarkusDev > logs/core.log 2>&1 &
CORE_PID=$!

# Wait for Java service
echo "Waiting for core service..."
until curl -f http://localhost:8081/q/health 2>/dev/null; do
  sleep 1
done

# Start BFFE
cd services/bffe
bun dev &
BFFE_PID=$!

# Wait for BFFE
echo "Waiting for BFFE..."
until curl -f http://localhost:3000/api/health 2>/dev/null; do
  sleep 1
done

# Start frontend
cd ../../packages/app
bun dev

# Cleanup on exit
trap "kill $CORE_PID $BFFE_PID" EXIT
```

### Testing

```bash
# Unit tests
cd services/bffe
bun test

# Integration tests (with Java services running)
bun test:integration

# Type checking
bun run lint
```

### Debugging

**BFFE Debugging (VS Code)**:

**.vscode/launch.json**:
```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "bun",
      "request": "launch",
      "name": "Debug BFFE",
      "program": "${workspaceFolder}/services/bffe/src/index.ts",
      "cwd": "${workspaceFolder}/services/bffe",
      "env": {
        "NODE_ENV": "development",
        "LOG_LEVEL": "debug"
      },
      "console": "integratedTerminal"
    }
  ]
}
```

---

## Deployment

### Docker Build

**services/bffe/Dockerfile**:
```dockerfile
FROM oven/bun:1 AS base
WORKDIR /app

# Install dependencies
COPY package.json bun.lockb ./
RUN bun install --frozen-lockfile --production

# Copy source
COPY src ./src
COPY tsconfig.json ./

# Build
RUN bun build src/index.ts --outdir=dist --target=bun

# Production image
FROM oven/bun:1-slim
WORKDIR /app

COPY --from=base /app/dist ./dist
COPY --from=base /app/node_modules ./node_modules
COPY public ./public

ENV NODE_ENV=production
EXPOSE 3000

CMD ["bun", "dist/index.js"]
```

### Build and Run

```bash
# Build
docker build -t flowcatalyst/bffe:latest services/bffe

# Run
docker run -p 3000:3000 \
  -e CORE_SERVICE_URL=http://core:8081 \
  flowcatalyst/bffe:latest
```

---

## Troubleshooting

### Problem: BFFE can't reach Java service

**Symptom**: "Failed to fetch" errors in BFFE logs

**Solution**:
```bash
# Check Java service is running
curl http://localhost:8081/q/health

# Check BFFE environment variable
echo $CORE_SERVICE_URL  # Should be http://localhost:8081

# Check network (Docker)
docker network inspect flowcatalyst_default
```

### Problem: CORS errors in browser

**Symptom**: "Access-Control-Allow-Origin" errors

**Solution**:
```typescript
// src/index.ts - Update CORS config
app.use('/api/*', cors({
  origin: process.env.CORS_ORIGINS?.split(',') || ['http://localhost:5173'],
  credentials: true,
  allowHeaders: ['Content-Type', 'Authorization'],
  allowMethods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
}))
```

### Problem: TypeScript errors

**Symptom**: "Cannot find module" or type errors

**Solution**:
```bash
# Reinstall dependencies
rm -rf node_modules bun.lockb
bun install

# Check tsconfig.json types
# Ensure "types": ["bun-types"] is present
```

---

## Next Steps

1. ✅ **You've completed basic setup**
2. ⏭️ **Add more routes** (credentials, dashboard)
3. ⏭️ **Add authentication** (OIDC, sessions)
4. ⏭️ **Add tests** (Vitest)
5. ⏭️ **Production deployment** (Docker, K8s)

**Full documentation**: [TYPESCRIPT-BFFE-ARCHITECTURE.md](./TYPESCRIPT-BFFE-ARCHITECTURE.md)

---

## Useful Commands

```bash
# Development
bun dev                    # Start with hot reload
bun run lint               # Type check
bun test                   # Run tests
bun test --watch          # Watch mode

# Production
bun run build             # Build for production
bun start                 # Run production build

# Docker
docker build -t bffe .    # Build image
docker run -p 3000:3000 bffe  # Run container

# Debugging
LOG_LEVEL=debug bun dev   # Verbose logging
bun --inspect src/index.ts  # Debug mode
```

---

**Questions?** See [TYPESCRIPT-BFFE-ARCHITECTURE.md](./TYPESCRIPT-BFFE-ARCHITECTURE.md) for full details.
