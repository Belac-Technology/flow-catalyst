# Hot Standby with Redisson

## Why Redisson?

After evaluating multiple approaches, we chose **Redisson** for distributed locking because:

1. **Automatic Watchdog** - Redisson automatically renews locks every 10 seconds while held (no manual scheduling needed)
2. **Clean API** - `tryLock()` returns boolean, `isHeldByCurrentThread()` for ownership verification
3. **Battle-tested** - Used in production by thousands of companies
4. **Edge case handling** - Handles network failures, Redis failover, crashes automatically
5. **Less code** - ~150 lines vs 300+ lines for manual implementation

## Quarkus Redis Client Limitations

We initially tried the Quarkus Redis client but hit these issues:

- `set()` with `SetArgs` returns `Void`, not `String` (can't detect success/failure)
- No clear API for Lua script execution with typed results
- Known bugs around SET NX return values (GitHub issue #32946)
- Would require complex Lua scripts to achieve atomicity

## Implementation

### Lock Manager (Redisson-based)

```java
@ApplicationScoped
public class LockManager {
    @Inject RedissonClient redissonClient;
    @Inject StandbyConfig standbyConfig;

    private RLock leaderLock;

    @PostConstruct
    void init() {
        this.leaderLock = redissonClient.getLock(standbyConfig.lockKey());
    }

    public boolean acquireLock() throws LockException {
        // Try to acquire (0 = no wait, 30s = TTL)
        // Watchdog auto-renews every 10s while held
        boolean acquired = leaderLock.tryLock(0, 30, TimeUnit.SECONDS);
        return acquired;
    }

    public boolean refreshLock() throws LockException {
        // Watchdog handles renewal, just verify we still hold it
        return leaderLock.isHeldByCurrentThread();
    }

    public boolean releaseLock() {
        if (leaderLock.isHeldByCurrentThread()) {
            leaderLock.unlock();
            return true;
        }
        return false;
    }
}
```

### Standby Service (Leader Election Logic)

```java
@ApplicationScoped
public class StandbyService {
    @Inject LockManager lockManager;

    private volatile boolean isPrimary = false;

    @Scheduled(every = "10s")
    void refreshLockTask() {
        if (isPrimary) {
            // Check if we still hold the lock
            boolean stillHolding = lockManager.refreshLock();
            if (!stillHolding) {
                // Lost leadership
                isPrimary = false;
                LOG.warning("Lost primary lock. Switching to standby");
            }
        } else {
            // Try to become primary
            boolean acquired = lockManager.acquireLock();
            if (acquired) {
                isPrimary = true;
                LOG.info("Acquired primary lock. Starting message processing");
            }
        }
    }
}
```

## Configuration

### Dependency

```kotlin
// build.gradle.kts
implementation("org.redisson:redisson-quarkus-30:3.40.2")
```

### Application Properties

```properties
# Hot standby configuration
standby.enabled=false
standby.instance-id=${HOSTNAME:instance-1}
standby.lock-key=message-router-primary-lock
standby.lock-ttl-seconds=30

# Redisson configuration
quarkus.redisson.single-server-config.address=redis://${REDIS_HOST:localhost}:${REDIS_PORT:6379}
quarkus.redisson.single-server-config.database=0
quarkus.redisson.single-server-config.connection-pool-size=4
quarkus.redisson.single-server-config.connection-minimum-idle-size=2

# Production: TLS with auth
%prod.quarkus.redisson.single-server-config.address=rediss://${REDIS_HOST}:${REDIS_PORT}
%prod.quarkus.redisson.single-server-config.username=${REDIS_USERNAME:}
%prod.quarkus.redisson.single-server-config.password=${REDIS_PASSWORD:}
```

## How It Works

### Lock Acquisition

1. **Primary instance** starts up, calls `leaderLock.tryLock(0, 30, TimeUnit.SECONDS)`
2. Redis executes `SET lock_key instance_id NX EX 30` atomically
3. Returns `true` - primary starts processing messages
4. **Redisson watchdog** automatically renews lock every 10 seconds

### Failover Sequence (Primary Crash)

```
Time 0s:    Primary holds lock (expires at 30s)
            Redisson watchdog schedules renewal at 10s
Time 0s:    Standby tries lock, fails (already held)
Time 10s:   Primary watchdog renews lock (now expires at 40s)
Time 10s:   Standby tries lock, fails
Time 20s:   Primary watchdog renews lock (now expires at 50s)
Time 27s:   PRIMARY CRASHES
Time 27s:   Watchdog stops (process dead)
Time 37s:   Lock naturally expires in Redis
Time 40s:   Standby refresh task runs
Time 40s:   Standby acquires lock successfully
Time 40s:   Standby becomes PRIMARY, starts processing

Total failover time: ~10-30 seconds (depending on crash timing)
```

### Failover Sequence (Primary Loses Lock)

```
Time 0s:    Primary holds lock, processing messages
Time 10s:   Primary refresh task checks lock
Time 10s:   Lock check FAILS (network partition, Redis issue, manual release)
Time 10s:   Primary logs CRITICAL error
Time 11s:   Primary shuts down (exit code 1)
Time 11s:   Kubernetes detects pod failure, starts new instance
Time 11s:   Standby acquires lock (within 0-30s depending on timing)
Time 11s:   Standby becomes PRIMARY, starts processing

Total failover time: ~0-30 seconds
Crashed primary: Automatically restarted by orchestrator
```

### Graceful Shutdown

```
Primary shutdown initiated:
1. @PreDestroy calls lockManager.releaseLock()
2. Lock removed from Redis immediately
3. Next cycle (< 10s), standby acquires lock
4. Zero message loss
```

## Advantages Over Manual Implementation

| Feature | Redisson | Manual Quarkus Redis |
|---------|----------|---------------------|
| Code complexity | ✅ ~150 lines | ❌ ~300+ lines |
| Automatic renewal | ✅ Built-in watchdog | ❌ Manual @Scheduled |
| Atomic operations | ✅ Handled internally | ⚠️ Requires Lua scripts |
| Thread safety | ✅ `isHeldByCurrentThread()` | ❌ Manual tracking |
| Network failures | ✅ Auto-handled | ⚠️ Manual error handling |
| Lock expiry detection | ✅ Built-in | ⚠️ Manual polling |
| Production-ready | ✅ Battle-tested | ⚠️ Custom implementation |

## Watchdog Details

Redisson's watchdog mechanism:

- **Default lease time**: If you pass `-1` to `tryLock()`, watchdog uses 30 seconds
- **Explicit lease time**: If you pass `30`, watchdog still works but uses your value
- **Renewal interval**: Watchdog renews every `leaseTime / 3` (e.g., 30s / 3 = 10s)
- **Auto-stop**: Watchdog stops when lock is released or process crashes
- **Thread-specific**: Only the thread that acquired the lock can unlock it

## Testing

### Unit Tests
- Mock `RedissonClient` to test lock acquisition logic
- Test failover scenarios (lock loss, Redis unavailable)
- Verify graceful shutdown releases lock

### Integration Tests
- Use TestContainers Redis for real lock behavior
- Start two instances, kill primary, verify standby takeover
- Test Redis connection loss and recovery

## Monitoring

Query the standby status:

```bash
curl http://localhost:8080/monitoring/standby-status
```

Response:
```json
{
  "standbyEnabled": true,
  "instanceId": "router-1",
  "role": "PRIMARY",
  "redisAvailable": true,
  "currentLockHolder": "locked",
  "lastSuccessfulRefresh": "2025-01-17T10:30:45Z",
  "hasWarning": false
}
```

## Migration Notes

If migrating from manual Redis client implementation:

1. **No code changes needed** in `StandbyService` - same interface
2. **Configuration changes**: Replace `quarkus.redis.*` with `quarkus.redisson.*`
3. **Dependency change**: Replace `quarkus-redis-client` with `redisson-quarkus-30`
4. **Behavior improvement**: Watchdog eliminates manual refresh complexity

## Related Documentation

- [STANDBY.md](STANDBY.md) - User guide and deployment examples
- [STANDBY_IMPLEMENTATION.md](STANDBY_IMPLEMENTATION.md) - Implementation details
- [Redisson Documentation](https://github.com/redisson/redisson/wiki/8.-Distributed-locks-and-synchronizers)
