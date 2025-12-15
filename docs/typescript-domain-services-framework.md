# TypeScript Domain Services Framework

A framework for building robust, event-driven domain services in TypeScript.

## Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| Runtime | Deno | TypeScript-native, secure by default |
| HTTP | Hono | Fast, middleware-based, OpenAPI support |
| Validation | Zod + @hono/zod-openapi | Runtime validation + types + OpenAPI |
| Database | Drizzle ORM | Type-safe SQL |
| Result | Custom | Explicit error handling |
| Utilities | Radashi | Tree-shakeable helpers |
| Logging | Pino | Structured logging |

## Core Principles

1. **All state changes go through UseCases** - No direct repository mutations
2. **Every mutation emits a domain event** - Guaranteed via UnitOfWork
3. **Atomic commits** - Aggregate + Event + AuditLog in one transaction
4. **Explicit errors** - Result type, no thrown exceptions for business logic
5. **Schema-first** - Zod schemas define types, validation, and OpenAPI docs

---

## Project Structure

```
src/
├── core/                    # Framework code (shared across services)
│   ├── result.ts
│   ├── context.ts
│   ├── events.ts
│   ├── unit-of-work.ts
│   ├── errors.ts
│   └── logging.ts
├── db/
│   ├── client.ts            # Drizzle client
│   ├── schema/              # Drizzle table definitions
│   └── migrate.ts
├── domains/
│   └── orders/              # Example domain
│       ├── schema.ts        # Zod schemas (commands, entities, events)
│       ├── repository.ts    # Data access
│       ├── use-cases/
│       │   ├── create-order.ts
│       │   ├── ship-order.ts
│       │   └── cancel-order.ts
│       ├── events.ts        # Domain events
│       └── routes.ts        # Hono routes with OpenAPI
├── app.ts                   # Hono app setup
└── main.ts                  # Entry point
```

---

## Core Types

### Result

```typescript
// src/core/result.ts

/**
 * Represents the outcome of an operation that can fail.
 * Success must only be created via UnitOfWork.commit() to ensure
 * domain events are always emitted with state changes.
 */
export type Result<T, E = UseCaseError> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: E };

/**
 * Create a success result.
 * NOTE: Only call this from UnitOfWork - not from use cases directly.
 * @internal
 */
export const ok = <T>(value: T): Result<T, never> =>
  ({ ok: true, value });

/**
 * Create a failure result.
 * Safe to call from anywhere.
 */
export const err = <E>(error: E): Result<never, E> =>
  ({ ok: false, error });

/**
 * Type guard for success
 */
export const isOk = <T, E>(result: Result<T, E>): result is { ok: true; value: T } =>
  result.ok;

/**
 * Type guard for failure
 */
export const isErr = <T, E>(result: Result<T, E>): result is { ok: false; error: E } =>
  !result.ok;
```

### UseCaseError

```typescript
// src/core/errors.ts

export type UseCaseError =
  | { code: 'NOT_FOUND'; message: string; details: Record<string, unknown> }
  | { code: 'VALIDATION'; message: string; details: Record<string, unknown> }
  | { code: 'BUSINESS_RULE'; message: string; details: Record<string, unknown> }
  | { code: 'CONFLICT'; message: string; details: Record<string, unknown> }
  | { code: 'INTERNAL'; message: string; details: Record<string, unknown> };

export const notFound = (message: string, details: Record<string, unknown> = {}): UseCaseError =>
  ({ code: 'NOT_FOUND', message, details });

export const validation = (message: string, details: Record<string, unknown> = {}): UseCaseError =>
  ({ code: 'VALIDATION', message, details });

export const businessRule = (message: string, details: Record<string, unknown> = {}): UseCaseError =>
  ({ code: 'BUSINESS_RULE', message, details });

export const conflict = (message: string, details: Record<string, unknown> = {}): UseCaseError =>
  ({ code: 'CONFLICT', message, details });

export const internal = (message: string, details: Record<string, unknown> = {}): UseCaseError =>
  ({ code: 'INTERNAL', message, details });
```

### ExecutionContext

```typescript
// src/core/context.ts

import { generateTsid } from './tsid.ts';

/**
 * Execution context for tracing and audit.
 * Passed through the entire request lifecycle.
 */
export interface ExecutionContext {
  /** Unique ID for this execution */
  readonly executionId: string;
  /** Correlation ID for distributed tracing (from upstream or generated) */
  readonly correlationId: string;
  /** ID of the operation that caused this one (for event chains) */
  readonly causationId: string;
  /** ID of the authenticated principal */
  readonly principalId: string;
  /** ISO timestamp of when execution started */
  readonly timestamp: string;
}

/**
 * Create a new execution context for a request.
 */
export const createContext = (
  principalId: string,
  correlationId?: string,
  causationId?: string,
): ExecutionContext => {
  const executionId = generateTsid();
  return {
    executionId,
    correlationId: correlationId ?? executionId,
    causationId: causationId ?? executionId,
    principalId,
    timestamp: new Date().toISOString(),
  };
};

/**
 * Create a child context (for event handlers triggering further operations).
 */
export const childContext = (
  parent: ExecutionContext,
  principalId?: string,
): ExecutionContext => ({
  executionId: generateTsid(),
  correlationId: parent.correlationId,
  causationId: parent.executionId,
  principalId: principalId ?? parent.principalId,
  timestamp: new Date().toISOString(),
});
```

### Domain Events

```typescript
// src/core/events.ts

import { ExecutionContext } from './context.ts';
import { generateTsid } from './tsid.ts';

/**
 * Base interface for all domain events.
 * Follows CloudEvents specification.
 */
export interface DomainEvent {
  /** Unique event ID */
  readonly id: string;
  /** Event type (e.g., "orders:order-created") */
  readonly type: string;
  /** Event source (e.g., "orders-service") */
  readonly source: string;
  /** Subject/aggregate (e.g., "order:0HZXEQ5Y8JY5Z") */
  readonly subject: string;
  /** ISO timestamp */
  readonly time: string;
  /** Spec version */
  readonly specversion: '1.0';
  /** Event payload */
  readonly data: unknown;

  // Tracing
  readonly correlationId: string;
  readonly causationId: string;
  readonly principalId: string;
}

/**
 * Create event metadata from execution context.
 */
export const eventMeta = (
  ctx: ExecutionContext,
  type: string,
  source: string,
  subject: string,
) => ({
  id: generateTsid(),
  type,
  source,
  subject,
  time: new Date().toISOString(),
  specversion: '1.0' as const,
  correlationId: ctx.correlationId,
  causationId: ctx.executionId,
  principalId: ctx.principalId,
});
```

---

## TSID Generation

```typescript
// src/core/tsid.ts

/**
 * Generate a time-sorted ID (TSID) as Crockford Base32.
 * - Lexicographically sortable
 * - URL-safe
 * - 13 characters
 */
export const generateTsid = (): string => {
  // Use a library like 'tsid-ts' or implement
  // For now, simple implementation:
  const timestamp = Date.now();
  const random = crypto.getRandomValues(new Uint8Array(8));
  const combined = new BigInt(timestamp) << 24n |
    BigInt(random[0]) << 16n |
    BigInt(random[1]) << 8n |
    BigInt(random[2]);
  return encodeCrockford(combined);
};

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

const encodeCrockford = (n: bigint): string => {
  let result = '';
  while (n > 0n) {
    result = CROCKFORD[Number(n % 32n)] + result;
    n = n / 32n;
  }
  return result.padStart(13, '0');
};
```

---

## Database Layer

### Drizzle Schema

```typescript
// src/db/schema/orders.ts

import { pgTable, text, integer, timestamp, jsonb } from 'drizzle-orm/pg-core';

export const orders = pgTable('orders', {
  id: text('id').primaryKey(),
  customerId: text('customer_id').notNull(),
  status: text('status').notNull().$type<'pending' | 'confirmed' | 'shipped' | 'cancelled'>(),
  totalAmount: integer('total_amount').notNull(),
  createdAt: timestamp('created_at').defaultNow().notNull(),
  updatedAt: timestamp('updated_at').defaultNow().notNull(),
});

export const orderItems = pgTable('order_items', {
  id: text('id').primaryKey(),
  orderId: text('order_id').notNull().references(() => orders.id),
  productId: text('product_id').notNull(),
  quantity: integer('quantity').notNull(),
  unitPrice: integer('unit_price').notNull(),
});

// Events table for event sourcing
export const events = pgTable('events', {
  id: text('id').primaryKey(),
  type: text('type').notNull(),
  source: text('source').notNull(),
  subject: text('subject').notNull(),
  time: timestamp('time').notNull(),
  specversion: text('specversion').notNull(),
  data: jsonb('data').notNull(),
  correlationId: text('correlation_id').notNull(),
  causationId: text('causation_id').notNull(),
  principalId: text('principal_id').notNull(),
});

// Audit log
export const auditLogs = pgTable('audit_logs', {
  id: text('id').primaryKey(),
  entityType: text('entity_type').notNull(),
  entityId: text('entity_id').notNull(),
  operation: text('operation').notNull(),
  operationData: jsonb('operation_data').notNull(),
  principalId: text('principal_id').notNull(),
  performedAt: timestamp('performed_at').defaultNow().notNull(),
});
```

### Drizzle Client

```typescript
// src/db/client.ts

import { drizzle } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import * as schema from './schema/index.ts';

const connectionString = Deno.env.get('DATABASE_URL')!;
const client = postgres(connectionString);

export const db = drizzle(client, { schema });
export type Database = typeof db;
```

---

## UnitOfWork

```typescript
// src/core/unit-of-work.ts

import { db, Database } from '../db/client.ts';
import { events, auditLogs } from '../db/schema/index.ts';
import { Result, ok } from './result.ts';
import { DomainEvent } from './events.ts';
import { UseCaseError, internal } from './errors.ts';
import { generateTsid } from './tsid.ts';
import { logger } from './logging.ts';

interface AggregateOperation {
  table: any;
  data: Record<string, unknown>;
  operation: 'insert' | 'update' | 'delete';
}

/**
 * Commit aggregate changes with domain event atomically.
 * This is the ONLY way to create a successful Result in use cases.
 */
export async function commit<T extends DomainEvent>(
  aggregates: AggregateOperation[],
  event: T,
  command: { constructor: { name: string } } & Record<string, unknown>,
): Promise<Result<T>> {
  const log = logger.child({
    executionId: event.causationId,
    correlationId: event.correlationId,
    eventType: event.type,
  });

  try {
    await db.transaction(async (tx) => {
      // 1. Persist aggregates
      for (const { table, data, operation } of aggregates) {
        switch (operation) {
          case 'insert':
            await tx.insert(table).values(data);
            break;
          case 'update':
            await tx.update(table)
              .set(data)
              .where(eq(table.id, data.id));
            break;
          case 'delete':
            await tx.delete(table)
              .where(eq(table.id, data.id));
            break;
        }
      }

      // 2. Persist domain event
      await tx.insert(events).values({
        id: event.id,
        type: event.type,
        source: event.source,
        subject: event.subject,
        time: new Date(event.time),
        specversion: event.specversion,
        data: event.data,
        correlationId: event.correlationId,
        causationId: event.causationId,
        principalId: event.principalId,
      });

      // 3. Persist audit log
      const [, entityType, entityId] = event.subject.split(':');
      await tx.insert(auditLogs).values({
        id: generateTsid(),
        entityType: entityType ?? 'unknown',
        entityId: entityId ?? 'unknown',
        operation: command.constructor.name,
        operationData: command,
        principalId: event.principalId,
        performedAt: new Date(),
      });
    });

    log.info({ eventId: event.id }, 'Committed transaction');
    return ok(event);

  } catch (error) {
    log.error({ error }, 'Transaction failed');
    return {
      ok: false,
      error: internal('Transaction failed', {
        message: error instanceof Error ? error.message : 'Unknown error'
      })
    };
  }
}

/**
 * Helper to build aggregate operations
 */
export const insert = (table: any, data: Record<string, unknown>): AggregateOperation =>
  ({ table, data, operation: 'insert' });

export const update = (table: any, data: Record<string, unknown>): AggregateOperation =>
  ({ table, data, operation: 'update' });

export const remove = (table: any, data: { id: string }): AggregateOperation =>
  ({ table, data, operation: 'delete' });
```

---

## UseCase Pattern

### Command Schema (Zod)

```typescript
// src/domains/orders/schema.ts

import { z } from 'zod';

// Commands
export const CreateOrderCommand = z.object({
  customerId: z.string().min(1),
  items: z.array(z.object({
    productId: z.string().min(1),
    quantity: z.number().int().positive(),
  })).min(1),
});
export type CreateOrderCommand = z.infer<typeof CreateOrderCommand>;

export const ShipOrderCommand = z.object({
  orderId: z.string().min(1),
  trackingNumber: z.string().min(1),
});
export type ShipOrderCommand = z.infer<typeof ShipOrderCommand>;

export const CancelOrderCommand = z.object({
  orderId: z.string().min(1),
  reason: z.string().min(1),
});
export type CancelOrderCommand = z.infer<typeof CancelOrderCommand>;

// Entity
export const Order = z.object({
  id: z.string(),
  customerId: z.string(),
  status: z.enum(['pending', 'confirmed', 'shipped', 'cancelled']),
  totalAmount: z.number(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});
export type Order = z.infer<typeof Order>;
```

### Domain Events

```typescript
// src/domains/orders/events.ts

import { DomainEvent, eventMeta } from '../../core/events.ts';
import { ExecutionContext } from '../../core/context.ts';

const SOURCE = 'orders-service';

export interface OrderCreatedEvent extends DomainEvent {
  type: 'orders:order-created';
  data: {
    orderId: string;
    customerId: string;
    totalAmount: number;
    itemCount: number;
  };
}

export const orderCreated = (
  ctx: ExecutionContext,
  data: OrderCreatedEvent['data'],
): OrderCreatedEvent => ({
  ...eventMeta(ctx, 'orders:order-created', SOURCE, `order:${data.orderId}`),
  data,
});

export interface OrderShippedEvent extends DomainEvent {
  type: 'orders:order-shipped';
  data: {
    orderId: string;
    trackingNumber: string;
  };
}

export const orderShipped = (
  ctx: ExecutionContext,
  data: OrderShippedEvent['data'],
): OrderShippedEvent => ({
  ...eventMeta(ctx, 'orders:order-shipped', SOURCE, `order:${data.orderId}`),
  data,
});

export interface OrderCancelledEvent extends DomainEvent {
  type: 'orders:order-cancelled';
  data: {
    orderId: string;
    reason: string;
  };
}

export const orderCancelled = (
  ctx: ExecutionContext,
  data: OrderCancelledEvent['data'],
): OrderCancelledEvent => ({
  ...eventMeta(ctx, 'orders:order-cancelled', SOURCE, `order:${data.orderId}`),
  data,
});
```

### UseCase Implementation

```typescript
// src/domains/orders/use-cases/create-order.ts

import { Result, err } from '../../../core/result.ts';
import { ExecutionContext } from '../../../core/context.ts';
import { commit, insert } from '../../../core/unit-of-work.ts';
import { notFound, validation } from '../../../core/errors.ts';
import { generateTsid } from '../../../core/tsid.ts';
import { CreateOrderCommand } from '../schema.ts';
import { OrderCreatedEvent, orderCreated } from '../events.ts';
import { orders, orderItems } from '../../../db/schema/orders.ts';
import { getProductPrices } from '../../products/repository.ts';

export async function createOrder(
  cmd: CreateOrderCommand,
  ctx: ExecutionContext,
): Promise<Result<OrderCreatedEvent>> {

  // 1. Validate business rules
  const productIds = cmd.items.map(i => i.productId);
  const prices = await getProductPrices(productIds);

  const missingProducts = productIds.filter(id => !prices.has(id));
  if (missingProducts.length > 0) {
    return err(notFound('Products not found', { productIds: missingProducts }));
  }

  // 2. Build aggregates
  const orderId = generateTsid();
  const now = new Date().toISOString();

  const items = cmd.items.map(item => ({
    id: generateTsid(),
    orderId,
    productId: item.productId,
    quantity: item.quantity,
    unitPrice: prices.get(item.productId)!,
  }));

  const totalAmount = items.reduce(
    (sum, item) => sum + item.quantity * item.unitPrice,
    0
  );

  const order = {
    id: orderId,
    customerId: cmd.customerId,
    status: 'pending' as const,
    totalAmount,
    createdAt: now,
    updatedAt: now,
  };

  // 3. Build event
  const event = orderCreated(ctx, {
    orderId,
    customerId: cmd.customerId,
    totalAmount,
    itemCount: items.length,
  });

  // 4. Atomic commit - only way to return success
  return commit(
    [
      insert(orders, order),
      ...items.map(item => insert(orderItems, item)),
    ],
    event,
    cmd,
  );
}
```

```typescript
// src/domains/orders/use-cases/ship-order.ts

import { Result, err } from '../../../core/result.ts';
import { ExecutionContext } from '../../../core/context.ts';
import { commit, update } from '../../../core/unit-of-work.ts';
import { notFound, businessRule } from '../../../core/errors.ts';
import { ShipOrderCommand } from '../schema.ts';
import { OrderShippedEvent, orderShipped } from '../events.ts';
import { findOrderById } from '../repository.ts';

export async function shipOrder(
  cmd: ShipOrderCommand,
  ctx: ExecutionContext,
): Promise<Result<OrderShippedEvent>> {

  // 1. Load aggregate
  const order = await findOrderById(cmd.orderId);
  if (!order) {
    return err(notFound('Order not found', { orderId: cmd.orderId }));
  }

  // 2. Validate business rules
  if (order.status !== 'confirmed') {
    return err(businessRule(
      'Order must be confirmed before shipping',
      { orderId: cmd.orderId, currentStatus: order.status }
    ));
  }

  // 3. Apply changes
  const updatedOrder = {
    ...order,
    status: 'shipped' as const,
    updatedAt: new Date().toISOString(),
  };

  // 4. Build event
  const event = orderShipped(ctx, {
    orderId: order.id,
    trackingNumber: cmd.trackingNumber,
  });

  // 5. Atomic commit
  return commit(
    [update(orders, updatedOrder)],
    event,
    cmd,
  );
}
```

---

## HTTP Layer with OpenAPI

### Route Definition

```typescript
// src/domains/orders/routes.ts

import { OpenAPIHono, createRoute, z } from '@hono/zod-openapi';
import { CreateOrderCommand, ShipOrderCommand, Order } from './schema.ts';
import { createOrder } from './use-cases/create-order.ts';
import { shipOrder } from './use-cases/ship-order.ts';
import { getContextFromRequest } from '../../core/context.ts';
import { mapErrorToResponse } from '../../core/errors.ts';

// Extend schemas with OpenAPI metadata
const CreateOrderRequest = CreateOrderCommand.openapi('CreateOrderRequest');
const ShipOrderRequest = ShipOrderCommand.openapi('ShipOrderRequest');
const OrderResponse = Order.openapi('Order');

const ErrorResponse = z.object({
  code: z.string(),
  message: z.string(),
  details: z.record(z.unknown()).optional(),
}).openapi('Error');

// Routes
const createOrderRoute = createRoute({
  method: 'post',
  path: '/orders',
  tags: ['Orders'],
  summary: 'Create a new order',
  description: 'Creates a new order with the specified items.',
  request: {
    body: {
      content: {
        'application/json': { schema: CreateOrderRequest },
      },
    },
  },
  responses: {
    201: {
      description: 'Order created successfully',
      content: {
        'application/json': { schema: OrderResponse },
      },
    },
    400: {
      description: 'Validation error',
      content: {
        'application/json': { schema: ErrorResponse },
      },
    },
    404: {
      description: 'Product not found',
      content: {
        'application/json': { schema: ErrorResponse },
      },
    },
  },
});

const shipOrderRoute = createRoute({
  method: 'post',
  path: '/orders/{orderId}/ship',
  tags: ['Orders'],
  summary: 'Ship an order',
  request: {
    params: z.object({
      orderId: z.string().openapi({ param: { name: 'orderId', in: 'path' } }),
    }),
    body: {
      content: {
        'application/json': {
          schema: z.object({
            trackingNumber: z.string()
          }).openapi('ShipOrderBody'),
        },
      },
    },
  },
  responses: {
    200: {
      description: 'Order shipped',
      content: {
        'application/json': { schema: OrderResponse },
      },
    },
    400: {
      description: 'Business rule violation',
      content: {
        'application/json': { schema: ErrorResponse },
      },
    },
    404: {
      description: 'Order not found',
      content: {
        'application/json': { schema: ErrorResponse },
      },
    },
  },
});

// Router
export const ordersRouter = new OpenAPIHono()
  .openapi(createOrderRoute, async (c) => {
    const cmd = c.req.valid('json');
    const ctx = getContextFromRequest(c);

    const result = await createOrder(cmd, ctx);

    if (!result.ok) {
      const [status, body] = mapErrorToResponse(result.error);
      return c.json(body, status);
    }

    // Fetch and return the created order
    const order = await findOrderById(result.value.data.orderId);
    return c.json(order, 201);
  })
  .openapi(shipOrderRoute, async (c) => {
    const { orderId } = c.req.valid('param');
    const { trackingNumber } = c.req.valid('json');
    const ctx = getContextFromRequest(c);

    const result = await shipOrder({ orderId, trackingNumber }, ctx);

    if (!result.ok) {
      const [status, body] = mapErrorToResponse(result.error);
      return c.json(body, status);
    }

    const order = await findOrderById(orderId);
    return c.json(order, 200);
  });
```

### Error Mapping

```typescript
// src/core/errors.ts (additions)

import { UseCaseError } from './errors.ts';

type HttpStatus = 400 | 404 | 409 | 500;

export const mapErrorToResponse = (
  error: UseCaseError
): [HttpStatus, { code: string; message: string; details?: Record<string, unknown> }] => {
  switch (error.code) {
    case 'NOT_FOUND':
      return [404, error];
    case 'VALIDATION':
      return [400, error];
    case 'BUSINESS_RULE':
      return [400, error];
    case 'CONFLICT':
      return [409, error];
    case 'INTERNAL':
    default:
      return [500, { code: 'INTERNAL', message: 'Internal server error' }];
  }
};
```

### App Setup

```typescript
// src/app.ts

import { OpenAPIHono } from '@hono/zod-openapi';
import { swaggerUI } from '@hono/swagger-ui';
import { logger as honoLogger } from 'hono/logger';
import { cors } from 'hono/cors';
import { ordersRouter } from './domains/orders/routes.ts';
import { logger } from './core/logging.ts';

export const app = new OpenAPIHono();

// Middleware
app.use('*', cors());
app.use('*', honoLogger());

// Request context middleware
app.use('*', async (c, next) => {
  const correlationId = c.req.header('x-correlation-id');
  c.set('correlationId', correlationId);
  await next();
});

// Domain routes
app.route('/api/v1', ordersRouter);

// OpenAPI documentation
app.doc('/openapi.json', {
  openapi: '3.1.0',
  info: {
    title: 'Orders Service API',
    version: '1.0.0',
    description: 'Domain service for order management',
  },
  servers: [
    { url: 'http://localhost:8000', description: 'Local' },
  ],
});

app.get('/docs', swaggerUI({ url: '/openapi.json' }));

// Health check
app.get('/health', (c) => c.json({ status: 'ok' }));
```

### Entry Point

```typescript
// src/main.ts

import { app } from './app.ts';
import { logger } from './core/logging.ts';

const port = parseInt(Deno.env.get('PORT') ?? '8000');

logger.info({ port }, 'Starting server');

Deno.serve({ port }, app.fetch);
```

---

## Logging

```typescript
// src/core/logging.ts

import pino from 'pino';

export const logger = pino({
  level: Deno.env.get('LOG_LEVEL') ?? 'info',
  formatters: {
    level: (label) => ({ level: label }),
  },
  timestamp: pino.stdTimeFunctions.isoTime,
});
```

---

## Testing

### UseCase Tests

```typescript
// src/domains/orders/use-cases/create-order.test.ts

import { describe, it, expect, beforeEach } from 'vitest';
import { createOrder } from './create-order.ts';
import { createContext } from '../../../core/context.ts';

describe('createOrder', () => {
  const ctx = createContext('test-principal');

  it('creates an order with valid items', async () => {
    const cmd = {
      customerId: 'customer-1',
      items: [
        { productId: 'product-1', quantity: 2 },
      ],
    };

    const result = await createOrder(cmd, ctx);

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.type).toBe('orders:order-created');
      expect(result.value.data.customerId).toBe('customer-1');
    }
  });

  it('fails when product not found', async () => {
    const cmd = {
      customerId: 'customer-1',
      items: [
        { productId: 'nonexistent', quantity: 1 },
      ],
    };

    const result = await createOrder(cmd, ctx);

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error.code).toBe('NOT_FOUND');
    }
  });

  it('fails when order is empty', async () => {
    const cmd = {
      customerId: 'customer-1',
      items: [],
    };

    // This would fail Zod validation before reaching the use case
    const parsed = CreateOrderCommand.safeParse(cmd);
    expect(parsed.success).toBe(false);
  });
});
```

---

## Configuration

```typescript
// src/config.ts

import { z } from 'zod';

const ConfigSchema = z.object({
  DATABASE_URL: z.string().url(),
  PORT: z.string().transform(Number).default('8000'),
  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
  SERVICE_NAME: z.string().default('domain-service'),
});

export const config = ConfigSchema.parse(Deno.env.toObject());
```

---

## Best Practices

### DO

1. **All mutations through UseCases** - Never call repository.insert() directly from routes
2. **One event per UseCase** - Each use case emits exactly one domain event
3. **Validate early** - Use Zod at the route level, business rules in the use case
4. **Load then modify** - Fetch aggregate, apply changes, commit
5. **Explicit errors** - Use Result type, never throw for business logic
6. **Structured logging** - Include correlationId, executionId in all logs
7. **Schema-first OpenAPI** - Define Zod schemas, derive types and docs

### DON'T

1. **Don't bypass UnitOfWork** - Never return `{ ok: true }` directly
2. **Don't mix concerns** - Routes handle HTTP, UseCases handle business logic
3. **Don't catch and swallow** - Let errors propagate as Result failures
4. **Don't use upsert** - You should know if you're creating or updating
5. **Don't skip events** - Every state change needs an event for downstream consumers

---

## File Templates

Use these as starting points for new domains:

```bash
# Create a new domain
mkdir -p src/domains/customers
touch src/domains/customers/{schema,events,repository,routes}.ts
mkdir -p src/domains/customers/use-cases
```

Each domain follows the same structure - schemas, events, repository, use-cases, routes.
