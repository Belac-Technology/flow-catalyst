package tech.flowcatalyst.messagerouter.model;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Statistics for a processing pool
 */
@Schema(description = "Statistics for a processing pool")
public record PoolStats(
    @Schema(description = "Pool identifier code", examples = {"POOL-HIGH", "POOL-MEDIUM", "POOL-LOW"})
    String poolCode,

    @Schema(description = "Total number of messages processed", examples = {"128647", "0", "5432"})
    long totalProcessed,

    @Schema(description = "Total number of successfully processed messages", examples = {"128637", "0", "5420"})
    long totalSucceeded,

    @Schema(description = "Total number of failed messages", examples = {"10", "0", "12"})
    long totalFailed,

    @Schema(description = "Total number of rate-limited messages", examples = {"0", "5", "100"})
    long totalRateLimited,

    @Schema(description = "Success rate (0.0 to 1.0)", examples = {"0.9999222679114165", "1.0", "0.0"})
    double successRate,

    @Schema(description = "Number of currently active workers", examples = {"10", "0", "5"})
    int activeWorkers,

    @Schema(description = "Number of available permits for new work", examples = {"0", "5", "2"})
    int availablePermits,

    @Schema(description = "Maximum concurrency for this pool", examples = {"10", "5", "2"})
    int maxConcurrency,

    @Schema(description = "Current number of messages in queue", examples = {"500", "0", "250"})
    int queueSize,

    @Schema(description = "Maximum queue capacity", examples = {"500", "500", "500"})
    int maxQueueCapacity,

    @Schema(description = "Average processing time in milliseconds", examples = {"103.82261537385249", "0.0", "250.5"})
    double averageProcessingTimeMs
) {}
