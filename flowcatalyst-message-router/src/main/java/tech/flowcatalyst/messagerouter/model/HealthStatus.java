package tech.flowcatalyst.messagerouter.model;

import java.time.Instant;

/**
 * Overall system health status
 */
public record HealthStatus(
    String status,
    Instant timestamp,
    long uptimeMillis,
    HealthDetails details
) {
    public record HealthDetails(
        int totalQueues,
        int healthyQueues,
        int totalPools,
        int healthyPools,
        int activeWarnings,
        int criticalWarnings,
        int circuitBreakersOpen,
        String degradationReason
    ) {}
}
