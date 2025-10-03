# HTTP Status Code Handling

## Overview

The message router handles HTTP status codes intelligently to prevent infinite retries on configuration errors while still retrying transient failures.

## Status Code Mappings

| Status Code | Result | Action | Rationale |
|------------|--------|--------|-----------|
| **200 OK** | `SUCCESS` | **ACK** | Message processed successfully |
| **400 Bad Request** | `ERROR_PROCESS` | **NACK** (30s visibility) | Could be transient malformation by router - retry |
| **401 Unauthorized** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Missing/invalid auth token - config error |
| **402 Payment Required** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Payment/billing issue - config error |
| **403 Forbidden** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Insufficient permissions - config error |
| **404 Not Found** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Endpoint doesn't exist - config error |
| **405 Method Not Allowed** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Wrong HTTP method - config error |
| **406 Not Acceptable** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Content negotiation failed - config error |
| **407 Proxy Auth Required** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Proxy authentication issue - config error |
| **408 Request Timeout** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Client timeout (not server) - config error |
| **409 Conflict** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Resource conflict - config error |
| **410 Gone** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Resource permanently deleted - config error |
| **411-499** | `ERROR_CONFIG` | **ACK** + CRITICAL warning | Various client errors - config errors |
| **500-599** | `ERROR_SERVER` | **NACK** (30s visibility) | Server error - transient, retry |

## Decision Logic

```java
if (statusCode == 200) {
    return SUCCESS;                    // ACK
} else if (statusCode >= 500) {
    return ERROR_SERVER;               // NACK with 30s retry
} else if (statusCode == 400) {
    return ERROR_PROCESS;              // NACK with 30s retry
} else if (statusCode >= 401 && statusCode < 500) {
    return ERROR_CONFIG;               // ACK + CRITICAL warning
} else {
    return ERROR_PROCESS;              // NACK with 30s retry
}
```

## Why This Matters

### Before (All 4xx → Retry Forever):
```
Message → 404 Not Found → NACK → Retry → 404 → NACK → Retry → ∞
├─ Blocks pool queue
├─ Wastes resources
└─ No visibility into misconfiguration
```

### After (4xx Config Errors → ACK):
```
Message → 404 Not Found → ACK + CRITICAL Warning
├─ Message removed from queue
├─ Pool continues processing
├─ Monitoring alert triggered
└─ Ops team notified of misconfiguration
```

## Monitoring

### Metrics
```
flowcatalyst_pool_errors_total{errorType="ERROR_CONFIG",pool="POOL-NAME"}
```

### Warnings Endpoint
```bash
curl http://localhost:8080/api/monitoring/warnings | jq
```

Expected output for 404:
```json
{
  "code": "CONFIGURATION",
  "level": "CRITICAL",
  "message": "Endpoint configuration error for message msg-123: ERROR_CONFIG - Target: http://localhost:8080/api/test/nonexistent",
  "component": "ProcessPool:POOL-MEDIUM"
}
```

## Special Cases

### 400 Bad Request - Why Retry?
- Could indicate message router constructed invalid request
- Might be transient serialization issue
- Worth retrying a few times before giving up

### 408 Request Timeout - Why Config Error?
- **408 = Client timeout** (not server timeout)
- Indicates client (message router) took too long
- Usually means connection/routing configuration issue
- Different from **504 Gateway Timeout** (server-side, would be 5xx)

### 429 Too Many Requests - Why Config Error?
- Rate limiting indicates configuration mismatch
- Message router should implement backoff
- But treating as config error prevents overwhelming destination
- Consider adding rate limit detection at router level

## Testing

### Test 404 Handling
```bash
curl --location 'http://localhost:8080/api/seed/messages' \
--header 'Content-Type: application/json' \
--data '{
  "count": 5,
  "queue": "flow-catalyst-high-priority.fifo",
  "endpoint": "http://localhost:8080/api/test/nonexistent"
}'
```

### Test 401 Handling
Use external endpoint with auth:
```bash
curl --location 'http://localhost:8080/api/seed/messages' \
--header 'Content-Type: application/json' \
--data '{
  "count": 5,
  "queue": "flow-catalyst-high-priority.fifo",
  "endpoint": "https://httpbin.org/basic-auth/user/pass"
}'
```

## Implementation Details

**Location:** `HttpMediator.java:88-110`

**Processing:** `ProcessPoolImpl.java:300-313`

**Result Enum:** `MediationResult.java:3-9`
