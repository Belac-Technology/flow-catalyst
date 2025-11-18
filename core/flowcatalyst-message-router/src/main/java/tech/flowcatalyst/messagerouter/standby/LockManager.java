package tech.flowcatalyst.messagerouter.standby;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import tech.flowcatalyst.messagerouter.config.StandbyConfig;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Manages Redis-based distributed lock for primary/standby election using Redisson.
 * Redisson's watchdog automatically refreshes the lock while held.
 * Only active when standby mode is enabled.
 */
@ApplicationScoped
public class LockManager {

    private static final Logger LOG = Logger.getLogger(LockManager.class.getName());

    @Inject
    StandbyConfig standbyConfig;

    @Inject
    Instance<RedissonClient> redissonClientInstance;

    private RLock leaderLock;

    @PostConstruct
    void init() {
        // Only initialize lock if standby is enabled and RedissonClient is available
        if (standbyConfig.enabled() && redissonClientInstance.isResolvable()) {
            RedissonClient redissonClient = redissonClientInstance.get();
            this.leaderLock = redissonClient.getLock(standbyConfig.lockKey());
        }
    }

    /**
     * Attempt to acquire the distributed lock.
     * Redisson watchdog automatically refreshes the lock every 10 seconds while held.
     *
     * @return true if lock was acquired, false if another instance holds it
     * @throws LockException if Redis is unavailable or connection fails
     */
    public boolean acquireLock() throws LockException {
        // If standby disabled, always return true (single instance mode)
        if (!standbyConfig.enabled() || leaderLock == null) {
            return true;
        }

        try {
            String instanceId = standbyConfig.instanceId();
            int ttlSeconds = standbyConfig.lockTtlSeconds();

            // Try to acquire lock with TTL (0 = don't wait, fail immediately if unavailable)
            // Redisson watchdog will auto-renew every lockWatchdogTimeout/3 (default 30s/3 = 10s)
            boolean acquired = leaderLock.tryLock(0, ttlSeconds, TimeUnit.SECONDS);

            if (acquired) {
                LOG.info("Lock acquired by instance: " + instanceId);
            } else {
                LOG.fine("Lock already held by another instance");
            }
            return acquired;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.severe("Interrupted while acquiring lock: " + e.getMessage());
            throw new LockException("Interrupted while acquiring lock", e);
        } catch (Exception e) {
            LOG.severe("Failed to acquire lock: " + e.getMessage());
            throw new LockException("Redis connection failed - unable to acquire lock", e);
        }
    }

    /**
     * Check if we currently hold the lock and refresh it if needed.
     * Redisson watchdog handles auto-renewal, this just verifies ownership.
     *
     * @return true if we still hold the lock, false if we lost it
     * @throws LockException if Redis is unavailable or connection fails
     */
    public boolean refreshLock() throws LockException {
        // If standby disabled, always return true (single instance mode)
        if (!standbyConfig.enabled() || leaderLock == null) {
            return true;
        }

        try {
            // Check if we still hold the lock
            // Redisson's watchdog automatically refreshes, so we just verify
            boolean holding = leaderLock.isHeldByCurrentThread();

            if (holding) {
                LOG.fine("Lock still held, watchdog handling renewal");
                return true;
            } else {
                LOG.warning("Lost lock - no longer held by this instance");
                return false;
            }

        } catch (Exception e) {
            LOG.severe("Failed to check lock status: " + e.getMessage());
            throw new LockException("Redis connection failed - unable to check lock", e);
        }
    }

    /**
     * Release the distributed lock immediately (for graceful shutdown).
     *
     * @return true if lock was released, false if we don't hold it
     */
    public boolean releaseLock() {
        // If standby disabled, nothing to release
        if (!standbyConfig.enabled() || leaderLock == null) {
            return false;
        }

        try {
            if (!leaderLock.isHeldByCurrentThread()) {
                LOG.fine("Lock not held by this instance, nothing to release");
                return false;
            }

            leaderLock.unlock();
            LOG.info("Lock released by instance: " + standbyConfig.instanceId());
            return true;

        } catch (Exception e) {
            LOG.warning("Error releasing lock (will expire automatically): " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if we currently hold the lock.
     *
     * @return true if we hold the lock, false otherwise
     * @throws LockException if Redis is unavailable
     */
    public boolean holdingLock() throws LockException {
        // If standby disabled, always return true (single instance mode)
        if (!standbyConfig.enabled() || leaderLock == null) {
            return true;
        }

        try {
            return leaderLock.isHeldByCurrentThread();
        } catch (Exception e) {
            LOG.severe("Failed to check lock status: " + e.getMessage());
            throw new LockException("Redis connection failed - unable to check lock", e);
        }
    }

    /**
     * Get current lock holder info (for monitoring/debugging).
     * Redisson doesn't expose holder details, so returns lock state.
     *
     * @return "locked" if held by any instance, "unlocked" if available, null on error
     */
    public String getCurrentLockHolder() {
        // If standby disabled, return single instance indicator
        if (!standbyConfig.enabled() || leaderLock == null) {
            return "single-instance";
        }

        try {
            return leaderLock.isLocked() ? "locked" : "unlocked";
        } catch (Exception e) {
            LOG.warning("Failed to get lock status: " + e.getMessage());
            return null;
        }
    }

    /**
     * Exception thrown when Redis operations fail.
     */
    public static class LockException extends Exception {
        public LockException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
