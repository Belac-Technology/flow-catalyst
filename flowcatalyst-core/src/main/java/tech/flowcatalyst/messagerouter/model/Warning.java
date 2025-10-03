package tech.flowcatalyst.messagerouter.model;

import java.time.Instant;

/**
 * Represents a system warning
 */
public record Warning(
    String id,
    String category,
    String severity,
    String message,
    Instant timestamp,
    String source,
    boolean acknowledged
) {}
