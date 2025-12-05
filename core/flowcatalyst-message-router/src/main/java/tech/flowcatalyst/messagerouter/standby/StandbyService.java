package tech.flowcatalyst.messagerouter.standby;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.Quarkus;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import tech.flowcatalyst.messagerouter.config.StandbyConfig;
import tech.flowcatalyst.messagerouter.model.Warning;
import tech.flowcatalyst.messagerouter.traffic.TrafficManagementService;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Manages hot standby mode with distributed primary/standby election using Redis.
 *
 * Responsibilities:
 * - Acquire lock on startup (becomes primary or waits as standby)
 * - Refresh lock periodically to maintain primary status
 * - Release lock gracefully on shutdown
 * - Detect Redis failures and mark system as unhealthy
 * - Fire warnings when Redis is unavailable
 */
@ApplicationScoped
public class StandbyService {

    private static final Logger LOG = Logger.getLogger(StandbyService.class.getName());

    @Inject
    StandbyConfig standbyConfig;

    @Inject
    LockManager lockManager;

    @Inject
    WarningService warningService;

    @Inject
    jakarta.enterprise.inject.Instance<TrafficManagementService> trafficManagementServiceInstance;

    // State tracking
    private volatile boolean isPrimary = false;
    private volatile Instant lastSuccessfulRefresh = null;
    private volatile boolean redisAvailable = true;
    private volatile String warningId = null;

    /**
     * Validate configuration on startup
     */
    void validateConfiguration(@Observes StartupEvent event) {
        if (standbyConfig.enabled()) {
            LOG.info("Hot standby mode enabled. Instance: " + standbyConfig.instanceId());
            LOG.info("Redis lock: " + standbyConfig.lockKey() +
                    " (TTL: " + standbyConfig.lockTtlSeconds() + "s, " +
                    "refresh: 10s - hardcoded)");
        } else {
            LOG.info("Hot standby mode disabled - running as single instance");
        }
    }

    /**
     * Attempt to acquire lock on startup.
     * Becomes primary if successful, standby if lock is held by another instance.
     */
    void onStartup(@Observes StartupEvent event) {
        if (!standbyConfig.enabled()) {
            // Single instance mode - always primary
            this.isPrimary = true;
            LOG.info("Single instance mode: Operating as primary");
            return;
        }

        try {
            if (lockManager.acquireLock()) {
                this.isPrimary = true;
                this.lastSuccessfulRefresh = Instant.now();
                this.redisAvailable = true;
                LOG.info("Acquired primary lock. Starting message processing.");

                // Register with load balancer as active instance
                registerWithLoadBalancer();
            } else {
                this.isPrimary = false;
                LOG.info("Standby mode: Waiting for primary to fail. Will attempt takeover in " +
                        standbyConfig.lockTtlSeconds() + " seconds.");

                // Deregister from load balancer since we're standby
                deregisterFromLoadBalancer();
            }
        } catch (LockManager.LockException e) {
            // Redis unavailable at startup
            this.redisAvailable = false;
            this.isPrimary = false;
            LOG.severe("Redis unavailable at startup. System will not process messages until Redis is restored.");
            fireRedisUnavailableWarning();

            // Deregister from load balancer since we can't process
            deregisterFromLoadBalancer();
        }
    }

    /**
     * Scheduled task to refresh the lock (primary) or attempt acquisition (standby).
     * Runs every 10 seconds by default (standby.refresh-interval-seconds).
     */
    @Scheduled(every = "10s")
    void refreshLockTask() {
        if (!standbyConfig.enabled()) {
            return;
        }

        try {
            if (isPrimary) {
                // Try to refresh our lock
                if (lockManager.refreshLock()) {
                    this.lastSuccessfulRefresh = Instant.now();
                    this.redisAvailable = true;

                    // Clear warning if Redis was previously unavailable
                    if (warningId != null) {
                        warningService.acknowledgeWarning(warningId);
                        warningId = null;
                    }
                } else {
                    // Lost the lock - something went wrong (network partition, Redis issue, manual intervention)
                    // Deregister from load balancer before shutting down
                    LOG.severe("CRITICAL: Primary lost lock unexpectedly. Deregistering from load balancer and shutting down.");
                    this.isPrimary = false;
                    deregisterFromLoadBalancer();

                    // Trigger application shutdown
                    // Use asyncExit to allow this scheduled task to complete gracefully
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000); // Brief delay to allow log message to flush
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        Quarkus.asyncExit(1); // Exit code 1 signals error to orchestrator
                    }, "standby-shutdown-thread").start();
                }
            } else {
                // Standby mode: try to acquire the lock
                if (lockManager.acquireLock()) {
                    this.isPrimary = true;
                    this.lastSuccessfulRefresh = Instant.now();
                    this.redisAvailable = true;
                    LOG.info("Acquired primary lock. Taking over message processing.");

                    // Register with load balancer as active instance
                    registerWithLoadBalancer();

                    // Clear warning if Redis was previously unavailable
                    if (warningId != null) {
                        warningService.acknowledgeWarning(warningId);
                        warningId = null;
                    }
                } else {
                    // Still waiting for primary to fail
                    LOG.fine("Still in standby. Waiting for primary lock to expire.");
                }
            }
        } catch (LockManager.LockException e) {
            // Redis connection failed
            LOG.severe("Redis connection failed during refresh: " + e.getMessage());
            this.redisAvailable = false;

            // Fire warning if we haven't already
            if (warningId == null) {
                fireRedisUnavailableWarning();
            }
        }
    }

    /**
     * Release lock gracefully on shutdown.
     * Allows standby instance to immediately take over without waiting for timeout.
     */
    void onShutdown(@Observes ShutdownEvent event) {
        if (!standbyConfig.enabled() || !isPrimary) {
            return;
        }

        try {
            // Deregister from load balancer first
            deregisterFromLoadBalancer();

            // Then release the lock
            lockManager.releaseLock();
            LOG.info("Primary lock released. Standby instance can now take over immediately.");
        } catch (Exception e) {
            LOG.warning("Error releasing lock during shutdown: " + e.getMessage());
            // Lock will expire naturally, that's ok
        }
    }

    /**
     * Check if this instance is the primary.
     * Used by QueueManager to decide whether to process messages.
     *
     * @return true if primary, false if standby
     */
    public boolean isPrimary() {
        return isPrimary;
    }

    /**
     * Check if Redis is currently available.
     * Used for health checks.
     *
     * @return true if Redis is available, false if unavailable
     */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }

    /**
     * Check if standby mode is enabled.
     *
     * @return true if standby.enabled=true
     */
    public boolean isStandbyEnabled() {
        return standbyConfig.enabled();
    }

    /**
     * Get current state information (for monitoring).
     */
    public StandbyStatus getStatus() {
        return new StandbyStatus(
                standbyConfig.instanceId(),
                isPrimary,
                redisAvailable,
                lastSuccessfulRefresh,
                lockManager.getCurrentLockHolder(),
                warningId != null
        );
    }

    /**
     * Fire CRITICAL warning when Redis becomes unavailable.
     * Prevents silent failures in distributed setup.
     */
    private void fireRedisUnavailableWarning() {
        try {
            String warningMessage = "Redis is unavailable and standby mode cannot function. " +
                    "Instance: " + standbyConfig.instanceId() + ". " +
                    "Manual intervention required. Server health checks will report FAILED.";

            this.warningId = java.util.UUID.randomUUID().toString();

            // Use the WarningService.addWarning method with proper signature
            warningService.addWarning(
                    this.warningId,
                    "CRITICAL",
                    "Standby Redis Connection Lost",
                    warningMessage
            );
            LOG.severe("CRITICAL: Redis unavailable - fired warning: " + warningId);
        } catch (Exception e) {
            LOG.severe("Failed to fire Redis unavailable warning: " + e.getMessage());
        }
    }

    /**
     * Register this instance with the load balancer.
     * Called when becoming PRIMARY.
     */
    private void registerWithLoadBalancer() {
        if (!trafficManagementServiceInstance.isResolvable()) {
            return;
        }

        try {
            TrafficManagementService trafficService = trafficManagementServiceInstance.get();
            trafficService.registerAsActive();
        } catch (Exception e) {
            LOG.warning("Failed to register with load balancer: " + e.getMessage());
            // Don't fail - traffic management is best-effort
        }
    }

    /**
     * Deregister this instance from the load balancer.
     * Called when becoming STANDBY or shutting down.
     */
    private void deregisterFromLoadBalancer() {
        if (!trafficManagementServiceInstance.isResolvable()) {
            return;
        }

        try {
            TrafficManagementService trafficService = trafficManagementServiceInstance.get();
            trafficService.deregisterFromActive();
        } catch (Exception e) {
            LOG.warning("Failed to deregister from load balancer: " + e.getMessage());
            // Don't fail - traffic management is best-effort
        }
    }

    /**
     * Status information for monitoring/debugging
     */
    public static class StandbyStatus {
        public final String instanceId;
        public final boolean isPrimary;
        public final boolean redisAvailable;
        public final Instant lastSuccessfulRefresh;
        public final String currentLockHolder;
        public final boolean hasWarning;

        public StandbyStatus(String instanceId, boolean isPrimary, boolean redisAvailable,
                           Instant lastSuccessfulRefresh, String currentLockHolder, boolean hasWarning) {
            this.instanceId = instanceId;
            this.isPrimary = isPrimary;
            this.redisAvailable = redisAvailable;
            this.lastSuccessfulRefresh = lastSuccessfulRefresh;
            this.currentLockHolder = currentLockHolder;
            this.hasWarning = hasWarning;
        }
    }
}
