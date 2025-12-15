package tech.flowcatalyst.outbox.model;

/**
 * Processing status of an outbox item.
 */
public enum OutboxStatus {
    /**
     * Item is waiting to be processed.
     */
    PENDING,

    /**
     * Item is currently being processed (locked by a processor).
     */
    PROCESSING,

    /**
     * Item has been successfully sent to FlowCatalyst.
     */
    COMPLETED,

    /**
     * Item failed after exhausting all retries.
     */
    FAILED
}
