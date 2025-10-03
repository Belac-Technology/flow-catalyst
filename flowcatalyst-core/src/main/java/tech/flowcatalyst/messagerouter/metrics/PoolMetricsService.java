package tech.flowcatalyst.messagerouter.metrics;

import tech.flowcatalyst.messagerouter.model.PoolStats;

import java.util.Map;

/**
 * Service for tracking processing pool metrics
 */
public interface PoolMetricsService {

    /**
     * Record that a message was submitted to a pool
     */
    void recordMessageSubmitted(String poolCode);

    /**
     * Record that a message processing started
     */
    void recordProcessingStarted(String poolCode);

    /**
     * Record that a message processing completed successfully
     */
    void recordProcessingSuccess(String poolCode, long durationMs);

    /**
     * Record that a message processing failed
     */
    void recordProcessingFailure(String poolCode, long durationMs, String errorType);

    /**
     * Record that a message was rejected due to rate limiting
     */
    void recordRateLimitExceeded(String poolCode);

    /**
     * Update gauge metrics for pool state
     */
    void updatePoolGauges(String poolCode, int activeWorkers, int availablePermits, int queueSize);

    /**
     * Get statistics for a specific pool
     */
    PoolStats getPoolStats(String poolCode);

    /**
     * Get statistics for all pools
     */
    Map<String, PoolStats> getAllPoolStats();
}
