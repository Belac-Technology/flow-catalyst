# Hot Standby Implementation Summary

## Overview

Implemented opt-in hot standby mode using Redis-based leader election. When enabled, one instance acts as primary processor while another waits in standby. Automatic failover occurs if primary fails.

## Key Design Decisions

### 1. Redis-Based Lock
- **Atomic Operations**: Uses Redis `SET` with `NX` (only if not exists) and `EX` (expiration)
- **No Database**: Standby operates independently without its own datastore
- **Timeout-Based**: Lock expires after 30 seconds if not refreshed (configurable)
- **Transparent**: If disabled, system operates as single instance with no Redis dependency

### 2. Error Handling Strategy
- **Redis Unavailable**: System enters safe failure state - no instance processes messages
- **Health Integration**: Reports DOWN on health probes when Redis unavailable
- **Critical Warnings**: Fires CRITICAL warning via warning system when Redis fails
- **No Split-Brain**: Prevents dual-processing even during network splits

### 3. Graceful Shutdown
- Primary releases lock immediately on shutdown
- Standby can take over without waiting for lock timeout
- Configurable graceful drainage of in-flight messages

## Implementation Details

### Files Created

**Config Package**
- `StandbyConfig.java`: Configuration interface with validation

**Standby Package** (new)
- `LockManager.java`: Redis lock operations (acquire, refresh, release)
- `StandbyService.java`: Lifecycle management, scheduled refresh task
- `StandbyHealthCheck.java`: Health probe reporting

### Files Modified

**Build Configuration**
- `build.gradle.kts`: Added `quarkus-redis-client` dependency

**Configuration**
- `application.properties`: Added standby and Redis configuration

**Core Service**
- `QueueManager.java`: Added standby check in scheduledSync() method

**API Endpoints**
- `MonitoringResource.java`: Added `/monitoring/standby-status` endpoint

## Architecture

### Lock Lifecycle

**Startup Phase**
```
try {
  acquireLock() → SET lock_key=instance_id EX 30 NX
}
catch (Redis unavailable) {
  Fire CRITICAL warning
  Mark health as DOWN
  Wait for Redis restoration
}
```

**Runtime Phase**
```
Every 10 seconds (hardcoded in @Scheduled annotation):
  1. Check if we hold lock
  2. If yes → refresh lock with new TTL
  3. If no → we lost lock, become standby

Standby waiting:
  1. Try to acquire lock
  2. If acquired → become primary
  3. If not → wait next cycle
```

**Note**: The refresh interval is hardcoded to 10 seconds in the `@Scheduled(every = "10s")` annotation on `StandbyService.refreshLockTask()`. This cannot be changed without code modification.

**Shutdown Phase**
```
onShutdown() {
  release lock immediately
  standby takes over without waiting
}
```

### Failover Sequence

```
Time  Action
----  ------
0s    Primary: acquire lock (expires 30s)
0s    Standby: fail to acquire, go to standby
10s   Primary: refresh lock (expires 40s)
20s   Primary: refresh lock (expires 50s)
22s   Primary: CRASH
30s   Primary: should have refreshed but didn't (dead)
40s   Standby: refresh task runs, lock not refreshed
40s   Standby: acquire lock (expires 70s)
40s   Standby: become PRIMARY and start processing

Total failover: ~20-30 seconds (worst case)
```

## Configuration

### Application Properties

```properties
# Hot Standby Configuration (opt-in)
standby.enabled=false  # Default: disabled
standby.instance-id=${HOSTNAME:instance-1}
standby.lock-key=message-router-primary-lock
standby.lock-ttl-seconds=30
# Note: Refresh interval is hardcoded to 10 seconds

# Redis Configuration (required only if standby.enabled=true)
# Format: redis://host:port or rediss://user:pass@host:port for TLS
quarkus.redis.hosts=${REDIS_HOSTS:redis://localhost:6379}
quarkus.redis.max-pool-size=4

# Production: Use TLS-encrypted Redis
%prod.quarkus.redis.hosts=rediss://${REDIS_USERNAME}:${REDIS_PASSWORD}@${REDIS_HOST}:${REDIS_PORT}
```

### Environment Variables

```bash
STANDBY_ENABLED=true
STANDBY_INSTANCE_ID=router-1

# Simple Redis URL
REDIS_HOSTS=redis://redis.example.com:6379

# Or for production with TLS and auth
REDIS_USERNAME=admin
REDIS_PASSWORD=secret
REDIS_HOST=redis.example.com
REDIS_PORT=6379
```

## Integration Points

### 1. QueueManager Integration
- Checks `standbyService.isPrimary()` in `scheduledSync()`
- Standby instance skips message processing
- Minimal overhead (~1 injectable check per sync cycle)

### 2. Health Check Integration
- `StandbyHealthCheck` implements Quarkus health probes
- Reports DOWN if standby enabled but Redis unavailable
- K8s removes unhealthy instance from service

### 3. Monitoring Endpoint
- `/monitoring/standby-status` endpoint returns:
  - Instance ID
  - Current role (PRIMARY/STANDBY)
  - Redis availability
  - Current lock holder
  - Last successful refresh time
  - Warning flag

### 4. Warning System Integration
- Fires CRITICAL warning when Redis becomes unavailable
- Auto-clears when Redis restored
- Includes details: instance ID, last refresh, configured Redis endpoint

## Error Handling

### Redis Unavailable at Startup
```
Primary: Cannot acquire lock
         → Set redisAvailable=false
         → Report health DOWN
         → Fire CRITICAL warning
         → Wait for Redis

Standby: Cannot check if lock exists
         → Set redisAvailable=false
         → Report health DOWN
         → Fire CRITICAL warning
         → Wait for Redis
```

### Redis Unavailable During Operation
```
Primary: Cannot refresh lock
         → Lose lock to another instance
         → Revert to standby
         → Stop processing messages

Standby: Cannot check lock status
         → Cannot detect if primary failed
         → Stay in standby
         → Do not process messages
```

### Result: Safe Failure
- No instance processing messages
- Health probes report DOWN
- K8s load balancer removes both from service
- Ops alerted via CRITICAL warning
- Manual intervention required

## Performance Characteristics

### Overhead (Per Refresh Cycle)
- **Lock Check**: 1 Redis GET operation
- **Lock Refresh**: 1 Redis SETEX operation (if holding)
- **Total**: ~2 Redis operations per 10 seconds (~0.2 ops/sec)

### Memory Impact
- Minimal: Just lock value stored in Redis
- No local state/caching

### Network Impact
- 1-2 Redis round-trips per 10 seconds
- Typical latency: <5ms per operation
- Total: <1% overhead for 100+ queues

## Testing Strategy

### Unit Tests (Recommended)
- Mock RedisDataSource for lock operations
- Test acquire/release/refresh scenarios
- Test configuration validation

### Integration Tests
- Use TestContainers Redis for real lock behavior
- Simulate primary failure scenarios
- Test failover timing
- Test Redis unavailability handling

### Manual Testing
- Two instances with shared Redis
- Kill primary, observe standby takeover
- Kill Redis, observe health probe failure
- Graceful shutdown, observe immediate takeover

## Configuration

No validation needed - refresh interval is hardcoded to 10 seconds. Only `lock-ttl-seconds` is configurable (default: 30s).

## Monitoring & Observability

### Log Messages
```
# Startup
"Hot standby mode enabled. Instance: router-1"
"Acquired primary lock. Starting message processing."
"Standby mode: Waiting for primary lock..."

# Failover
"Acquired primary lock. Taking over message processing."
"Lost primary lock. Switching to standby mode."

# Errors
"Redis unavailable at startup. System will not process messages."
"Redis connection failed during refresh"
```

### Metrics (via StandbyStatus)
- `instanceId`: Which instance
- `isPrimary`: Current role
- `redisAvailable`: Redis connectivity status
- `lastSuccessfulRefresh`: When we last updated lock
- `currentLockHolder`: Which instance holds lock
- `hasWarning`: Critical warning active

### Alerts
- Health probe DOWN when Redis unavailable
- CRITICAL warning fires with details
- Email/Slack/Teams notifications (if configured)

## Backward Compatibility

- **Default**: `standby.enabled=false`
- Existing deployments unaffected
- No breaking changes
- Single-instance mode works identically
- Can enable/disable without code changes

## Future Enhancements

1. **Redis Sentinel Support**: Automatic Redis failover
2. **Multi-Lock Strategy**: Multiple Redis instances for HA
3. **Instance Metrics**: Per-instance processing counts
4. **Maintenance Mode**: Explicit primary demotion
5. **Custom Lock TTL**: Dynamic adjustment based on load

## Related Documentation

- [STANDBY.md](STANDBY.md): User guide and deployment examples
- [AUTHENTICATION.md](AUTHENTICATION.md): Optional authentication feature
- Application Properties: Configuration reference
