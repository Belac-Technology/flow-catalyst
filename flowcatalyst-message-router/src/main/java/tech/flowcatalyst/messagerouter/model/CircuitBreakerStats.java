package tech.flowcatalyst.messagerouter.model;

/**
 * Circuit breaker statistics
 */
public record CircuitBreakerStats(
    String name,
    String state,
    long successfulCalls,
    long failedCalls,
    long rejectedCalls,
    double failureRate,
    int bufferedCalls,
    int bufferSize
) {}
