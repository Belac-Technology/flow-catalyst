# Dispatch Jobs

## Overview

Dispatch Jobs is a reliable webhook delivery system built on top of Flow Catalyst's message router. It provides:

- **Signed Webhooks** - HMAC SHA-256 signatures with bearer token authentication
- **Retry Logic** - Configurable retry attempts with tracking
- **Searchable Metadata** - Indexed key-value metadata for efficient querying
- **Full Audit Trail** - Complete history of delivery attempts
- **Database Agnostic** - Clean repository layer supporting PostgreSQL and MongoDB

**See:** [Database Strategy](database-strategy.md) for database design decisions and migration path.

## Architecture

### Components

1. **Entities**
   - `DispatchJob` - Main job entity with routing, scheduling, and status
   - `DispatchCredentials` - Webhook authentication credentials (cached)
   - `DispatchAttempt` - Individual delivery attempt records
   - `DispatchJobMetadata` - Searchable metadata key-value pairs

2. **Services**
   - `DispatchJobService` - Job orchestration and queue integration
   - `CredentialsService` - Credential management with in-memory caching
   - `WebhookSigner` - HMAC signature generation
   - `WebhookDispatcher` - HTTP webhook delivery

3. **Mediator**
   - `DispatchJobMediator` - Implements message router's `Mediator` interface
   - Processes jobs from the `DISPATCH-POOL`

### Message Flow

```
1. Create Dispatch Job (API)
   ↓
2. Save to database
   ↓
3. Send MessagePointer to SQS (flow-catalyst-dispatch.fifo)
   ↓
4. Message Router picks up message
   ↓
5. Routes to DISPATCH-POOL → DispatchJobMediator
   ↓
6. Mediator calls internal processing endpoint (/api/dispatch/process)
   ↓
7. WebhookDispatcher signs & sends webhook
   ↓
8. Record attempt in database
   ↓
9. Return status (200 = success/exhausted, 400 = retry)
```

## Database Schema

**Note:** See [Database Strategy](database-strategy.md) for detailed design decisions, TSID implementation, and future MongoDB migration path.

### dispatch_job
- **Identity**: id (BIGINT - TSID), externalId (VARCHAR(32)), createdAt, updatedAt
- **Classification**: source, type, groupId
- **Target**: targetUrl, protocol, headers (ElementCollection)
- **Payload**: payload (TEXT), payloadContentType
- **Credentials**: credentials_id (BIGINT reference - NO FK constraint)
- **Execution**: status, maxRetries, retryStrategy, scheduledFor, expiresAt
- **Tracking**: attemptCount, lastAttemptAt, completedAt, durationMillis, lastError
- **Idempotency**: idempotencyKey

**Indexes:**
- idx_dispatch_external_id (external_id)
- idx_dispatch_status (status)
- idx_dispatch_source (source)
- idx_dispatch_type (type)
- idx_dispatch_group_id (group_id)
- idx_dispatch_scheduled (scheduled_for)
- idx_dispatch_created (created_at)

### dispatch_job_metadata
- **Identity**: id (BIGINT - TSID)
- **Relationship**: dispatch_job_id (BIGINT reference - NO FK constraint)
- **Data**: key, value

**Indexes:**
- idx_metadata_job_id (dispatch_job_id)
- idx_metadata_key_value (meta_key, meta_value) - Composite for efficient searches

### dispatch_attempt
- **Identity**: id (BIGINT - TSID)
- **Relationship**: dispatch_job_id (BIGINT reference - NO FK constraint)
- **Timing**: attemptNumber, attemptedAt, completedAt, durationMillis
- **Result**: status, responseCode, responseBody, errorMessage, errorStackTrace

**Indexes:**
- idx_attempt_job_id (dispatch_job_id)
- idx_attempt_status (status)
- idx_attempt_attempted_at (attempted_at)

### dispatch_credentials
- **Identity**: id (BIGINT - TSID)
- **Security**: bearerToken, signingSecret, algorithm
- **Timestamps**: createdAt, updatedAt

**Caching:** Credentials are cached in memory using Quarkus Cache for fast lookups during job processing.

### dispatch_job_headers
- **ElementCollection** table for HTTP headers
- **Columns**: dispatch_job_id (BIGINT reference), header_name, header_value

### Design Decisions

**TSID (Time-Sorted ID):**
- Primary keys use TSID as BIGINT (8 bytes) instead of UUID (16 bytes)
- Time-sortable, efficient indexing, monotonic generation
- `externalId` field allows clients to provide their own UUID/ULID (up to 32 chars)

**No Foreign Key Constraints:**
- Intentional design for microservice architecture
- Application-level referential integrity
- Enables independent scaling and eventual consistency
- See [Database Strategy](database-strategy.md) for complete rationale

**Aggregate-Oriented:**
- DispatchJob is the aggregate root
- Metadata and attempts are part of the job aggregate
- Credentials referenced by ID (separate bounded context)

## API Endpoints

### Dispatch Job Management

#### Create Dispatch Job
```http
POST /api/dispatch/jobs
Content-Type: application/json

{
  "source": "order-service",
  "type": "order.created",
  "groupId": "order-12345",
  "metadata": {
    "tenantId": "acme-corp",
    "userId": "user-789",
    "priority": "high"
  },
  "targetUrl": "https://partner.com/webhooks/orders",
  "protocol": "HTTP_WEBHOOK",
  "headers": {
    "X-Custom-Header": "value"
  },
  "payload": "{\"orderId\":\"12345\",\"amount\":99.99}",
  "payloadContentType": "application/json",
  "credentialsId": "cred-uuid-here",
  "maxRetries": 3,
  "retryStrategy": "exponential",
  "scheduledFor": "2025-10-04T15:00:00Z",
  "queueUrl": "http://localhost:9324/000000000000/flow-catalyst-dispatch.fifo"
}
```

**Response (201):**
```json
{
  "id": "job-uuid",
  "source": "order-service",
  "type": "order.created",
  "status": "PENDING",
  "attemptCount": 0,
  "createdAt": "2025-10-04T14:30:00Z",
  ...
}
```

#### Get Dispatch Job
```http
GET /api/dispatch/jobs/{id}
```

**Response (200):**
```json
{
  "id": "job-uuid",
  "status": "COMPLETED",
  "attemptCount": 1,
  "completedAt": "2025-10-04T14:30:05Z",
  "durationMillis": 5234,
  ...
}
```

#### Search Dispatch Jobs
```http
GET /api/dispatch/jobs?status=FAILED&source=order-service&page=0&size=20
```

**Query Parameters:**
- `status` - Filter by DispatchStatus
- `source` - Filter by source system
- `type` - Filter by job type
- `groupId` - Filter by group ID
- `createdAfter` - Filter by creation date (ISO8601)
- `createdBefore` - Filter by creation date (ISO8601)
- `page` - Page number (default: 0)
- `size` - Page size (default: 20, max: 100)

**Response (200):**
```json
{
  "items": [...],
  "page": 0,
  "size": 20,
  "totalItems": 150,
  "totalPages": 8
}
```

#### Get Job Attempts
```http
GET /api/dispatch/jobs/{id}/attempts
```

**Response (200):**
```json
[
  {
    "id": "attempt-uuid-1",
    "attemptNumber": 1,
    "status": "FAILURE",
    "responseCode": 500,
    "errorMessage": "HTTP 500",
    "durationMillis": 1234,
    "attemptedAt": "2025-10-04T14:30:00Z",
    "completedAt": "2025-10-04T14:30:01Z"
  },
  {
    "id": "attempt-uuid-2",
    "attemptNumber": 2,
    "status": "SUCCESS",
    "responseCode": 200,
    "durationMillis": 890,
    "attemptedAt": "2025-10-04T14:31:00Z",
    "completedAt": "2025-10-04T14:31:01Z"
  }
]
```

### Credentials Management (Admin)

#### Create Credentials
```http
POST /api/admin/dispatch/credentials
Content-Type: application/json

{
  "bearerToken": "sk_live_abc123...",
  "signingSecret": "whsec_xyz789...",
  "algorithm": "HMAC_SHA256"
}
```

**Response (201):**
```json
{
  "id": "cred-uuid",
  "algorithm": "HMAC_SHA256",
  "createdAt": "2025-10-04T14:00:00Z",
  "updatedAt": "2025-10-04T14:00:00Z"
}
```

#### Get Credentials (No Sensitive Data)
```http
GET /api/admin/dispatch/credentials/{id}
```

#### Delete Credentials
```http
DELETE /api/admin/dispatch/credentials/{id}
```

## Webhook Security

### Signature Generation

Each webhook includes:
1. **Authorization Header**: `Bearer {bearerToken}`
2. **Signature Header**: `X-FLOWCATALYST-SIGNATURE`
3. **Timestamp Header**: `X-FLOWCATALYST-TIMESTAMP`

**Signature Algorithm:**
```
timestamp = Current ISO8601 datetime (millisecond precision)
signaturePayload = timestamp + payload
signature = HMAC-SHA256(signaturePayload, signingSecret)
signature = hexEncode(signature)
```

**Example:**
```
Timestamp: 2025-10-04T14:30:45.123Z
Payload: {"orderId":"12345","amount":99.99}
Signature Payload: 2025-10-04T14:30:45.123Z{"orderId":"12345","amount":99.99}
Signature: a1b2c3d4e5f6...
```

### Webhook Request
```http
POST /webhooks/orders HTTP/1.1
Host: partner.com
Authorization: Bearer sk_live_abc123...
X-FLOWCATALYST-SIGNATURE: a1b2c3d4e5f6...
X-FLOWCATALYST-TIMESTAMP: 2025-10-04T14:30:45.123Z
Content-Type: application/json

{"orderId":"12345","amount":99.99}
```

### Signature Verification (Receiver Side)

```java
public boolean verifyWebhook(
    String receivedSignature,
    String receivedTimestamp,
    String payload,
    String signingSecret) {

    // 1. Check timestamp freshness (prevent replay attacks)
    Instant timestamp = Instant.parse(receivedTimestamp);
    long ageSeconds = Duration.between(timestamp, Instant.now()).getSeconds();
    if (ageSeconds > 300) return false; // Too old

    // 2. Recreate signature
    String signaturePayload = receivedTimestamp + payload;
    String expectedSignature = hmacSha256(signaturePayload, signingSecret);

    // 3. Constant-time comparison
    return MessageDigest.isEqual(
        receivedSignature.getBytes(),
        expectedSignature.getBytes()
    );
}
```

## Job Status Flow

```
PENDING → IN_PROGRESS → COMPLETED (success)
                      ↓
                   FAILED → IN_PROGRESS → ... (retry)
                      ↓
                   ERROR (max attempts exhausted)

PENDING → CANCELLED (manual cancellation)
```

**Status Meanings:**
- **PENDING** - Created, waiting to be processed
- **IN_PROGRESS** - Currently being dispatched
- **COMPLETED** - Successfully delivered
- **FAILED** - Failed, will retry (attempts < maxRetries)
- **ERROR** - Failed, max attempts exhausted
- **CANCELLED** - Manually cancelled

## Retry Logic

### Process Flow
1. Job is processed via `/api/dispatch/process` endpoint
2. Webhook is sent, attempt is recorded
3. If successful (HTTP 200-299):
   - Job status → COMPLETED
   - Return HTTP 200
4. If failed and attempts < maxRetries:
   - Job status → FAILED
   - Return HTTP 400 (triggers message router retry)
5. If failed and attempts >= maxRetries:
   - Job status → ERROR
   - Return HTTP 200 (prevents further retries)

### Message Router Integration
- HTTP 400 response = message stays in queue (visibility timeout retry)
- HTTP 200 response = message deleted from queue
- Message router handles retry delays via queue configuration

## Metadata Queries

### Find by Single Metadata Field
```java
dispatchJobRepository.findByMetadata("tenantId", "acme-corp");
```

### Find by Multiple Metadata Fields (AND)
```java
Map<String, String> filters = Map.of(
    "tenantId", "acme-corp",
    "priority", "high"
);
dispatchJobRepository.findByMetadataFilters(filters);
```

### SQL Query Examples
```sql
-- Find all jobs for a tenant
SELECT DISTINCT j.*
FROM dispatch_job j
JOIN dispatch_job_metadata m ON m.dispatch_job_id = j.id
WHERE m.meta_key = 'tenantId' AND m.meta_value = 'acme-corp';

-- Find high-priority jobs for a tenant
SELECT j.*
FROM dispatch_job j
WHERE EXISTS (
    SELECT 1 FROM dispatch_job_metadata m1
    WHERE m1.dispatch_job_id = j.id
    AND m1.meta_key = 'tenantId'
    AND m1.meta_value = 'acme-corp'
)
AND EXISTS (
    SELECT 1 FROM dispatch_job_metadata m2
    WHERE m2.dispatch_job_id = j.id
    AND m2.meta_key = 'priority'
    AND m2.meta_value = 'high'
);
```

## MongoDB Migration

The system is designed for easy migration to MongoDB:

### Current (PostgreSQL)
```java
@OneToMany(mappedBy = "dispatchJob", cascade = CascadeType.ALL)
public List<DispatchJobMetadata> metadata;

@ElementCollection
@CollectionTable(name = "dispatch_job_headers")
public Map<String, String> headers;
```

### Future (MongoDB with Panache)
```java
// Automatically becomes embedded documents
// No code changes needed!
public List<DispatchJobMetadata> metadata;
public Map<String, String> headers;
```

**Benefits:**
- Same entity structure
- Same Panache queries
- Same DTOs and API contracts
- Seamless database swap

## Configuration

### Application Properties
```properties
# Database (PostgreSQL)
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/flowcatalyst
quarkus.datasource.username=postgres
quarkus.datasource.password=postgres

# Hibernate
quarkus.hibernate-orm.database.generation=update
%dev.quarkus.hibernate-orm.log.sql=true

# Cache (for credentials)
quarkus.cache.type=caffeine
```

### Message Router Config
Add to `/api/config`:
```json
{
  "queues": [
    {
      "queueUri": "http://localhost:9324/000000000000/flow-catalyst-dispatch.fifo"
    }
  ],
  "processingPools": [
    {
      "code": "DISPATCH-POOL",
      "concurrency": 5
    }
  ]
}
```

## Example Use Cases

### Event Subscribers (One Event → Multiple Webhooks)
```java
// Order created event needs to notify 3 subscribers
Order order = getOrder();
List<Subscriber> subscribers = getSubscribers("order.created");

for (Subscriber sub : subscribers) {
    CreateDispatchJobRequest request = new CreateDispatchJobRequest(
        "order-service",          // source
        "order.created",          // type
        "order-" + order.getId(), // groupId
        Map.of(
            "tenantId", order.getTenantId(),
            "eventVersion", "v1"
        ),
        sub.getWebhookUrl(),      // targetUrl
        DispatchProtocol.HTTP_WEBHOOK,
        null,                     // headers
        serializeOrder(order),    // payload
        "application/json",       // payloadContentType
        sub.getCredentialsId(),   // credentialsId
        3,                        // maxRetries
        "exponential",            // retryStrategy
        null,                     // scheduledFor (immediate)
        null,                     // expiresAt
        order.getId(),            // idempotencyKey
        DISPATCH_QUEUE_URL        // queueUrl
    );

    dispatchJobService.createDispatchJob(request);
}
```

### Reliable Task Execution
```java
// Send email with retry
CreateDispatchJobRequest request = new CreateDispatchJobRequest(
    "email-service",
    "email.send",
    "user-notification-" + userId,
    Map.of("userId", userId, "priority", "high"),
    "https://email-service/send",
    DispatchProtocol.HTTP_WEBHOOK,
    null,
    serializeEmailData(emailData),
    "application/json",
    emailCredentialsId,
    5,
    "exponential",
    Instant.now().plus(5, ChronoUnit.MINUTES), // Delayed
    Instant.now().plus(1, ChronoUnit.DAYS),    // Expires
    emailId,
    DISPATCH_QUEUE_URL
);

dispatchJobService.createDispatchJob(request);
```

## Performance Considerations

### Indexing Strategy
- **Most Important**: Composite indexes on commonly filtered fields
- **Metadata**: Separate table allows independent index scaling
- **Attempts**: Indexed on job_id for fast lookups

### Caching
- Credentials cached in memory (Caffeine)
- Cache invalidation on delete/update
- Reduces database load during high-volume dispatch

### Concurrency
- `DISPATCH-POOL` runs on virtual threads
- Default concurrency: 5 (configurable)
- Scale by adjusting pool concurrency in message router config

## Monitoring

### Key Metrics to Track
- Jobs created per minute
- Success rate by source/type
- Average delivery time
- Retry counts
- Jobs in ERROR status
- Failed delivery patterns

### Queries for Dashboards
```sql
-- Success rate by source (last 24h)
SELECT
    source,
    COUNT(*) as total,
    SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) as successful,
    ROUND(100.0 * SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) / COUNT(*), 2) as success_rate
FROM dispatch_job
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY source;

-- Average delivery duration by type
SELECT
    type,
    AVG(duration_millis) as avg_duration_ms,
    MAX(duration_millis) as max_duration_ms
FROM dispatch_job
WHERE status = 'COMPLETED'
GROUP BY type;

-- Jobs requiring attention
SELECT * FROM dispatch_job
WHERE status = 'ERROR'
   OR (status = 'FAILED' AND attempt_count >= max_retries - 1);
```

## Security Best Practices

1. **Credential Storage**: Store credentials encrypted at rest (future enhancement)
2. **Credential Rotation**: Plan for credential expiration and replacement
3. **Timestamp Validation**: Receivers should validate timestamp freshness (5-minute window)
4. **Constant-Time Comparison**: Prevent timing attacks when verifying signatures
5. **HTTPS Only**: Always use HTTPS for targetUrl in production
6. **Secret Generation**: Use cryptographically secure random for signing secrets

## Future Enhancements

- [ ] Credential encryption at rest
- [ ] Credential rotation support
- [ ] Additional protocols (gRPC, SNS, S3)
- [ ] Dead letter queue for ERROR status jobs
- [ ] Webhook endpoint validation
- [ ] Batch job creation API
- [ ] Job cancellation API
- [ ] Metrics export (Prometheus)
- [ ] OpenTelemetry tracing
