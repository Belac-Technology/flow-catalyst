package tech.flowcatalyst.messagerouter.model;

/**
 * Statistics for a processing pool
 */
public record PoolStats(
    String poolCode,
    long totalProcessed,
    long totalSucceeded,
    long totalFailed,
    long totalRateLimited,
    double successRate,
    int activeWorkers,
    int availablePermits,
    int maxConcurrency,
    int queueSize,
    int maxQueueCapacity,
    double averageProcessingTimeMs
) {}
