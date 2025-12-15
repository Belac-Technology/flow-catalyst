package tech.flowcatalyst.outbox.model;

import java.time.Instant;

/**
 * Represents an item in the outbox table/collection.
 * Items are polled from customer databases and sent to FlowCatalyst APIs.
 */
public record OutboxItem(
    /**
     * Unique identifier for the outbox item (TSID format).
     */
    String id,

    /**
     * Type of item: EVENT or DISPATCH_JOB.
     */
    OutboxItemType type,

    /**
     * Message group for FIFO ordering.
     * Items within the same group are processed in order.
     */
    String messageGroup,

    /**
     * JSON payload to send to FlowCatalyst API.
     */
    String payload,

    /**
     * Current processing status.
     */
    OutboxStatus status,

    /**
     * Number of times this item has been retried.
     */
    int retryCount,

    /**
     * When the item was created in the outbox.
     */
    Instant createdAt,

    /**
     * When the item was last picked up for processing.
     */
    Instant processedAt,

    /**
     * Error message if the item failed.
     */
    String errorMessage
) {
    /**
     * Creates a new OutboxItem with updated status.
     */
    public OutboxItem withStatus(OutboxStatus newStatus) {
        return new OutboxItem(id, type, messageGroup, payload, newStatus, retryCount, createdAt, processedAt, errorMessage);
    }

    /**
     * Creates a new OutboxItem with incremented retry count.
     */
    public OutboxItem withRetry() {
        return new OutboxItem(id, type, messageGroup, payload, OutboxStatus.PENDING, retryCount + 1, createdAt, null, errorMessage);
    }

    /**
     * Creates a new OutboxItem marked as failed with error message.
     */
    public OutboxItem withFailure(String error) {
        return new OutboxItem(id, type, messageGroup, payload, OutboxStatus.FAILED, retryCount, createdAt, processedAt, error);
    }
}
