package tech.flowcatalyst.queue;

import java.util.List;

/**
 * Abstraction for publishing messages to a queue.
 * Implementations support different queue types (SQS, ActiveMQ, Embedded).
 */
public interface QueuePublisher {

    /**
     * Publish a single message to the queue.
     *
     * @param message The message to publish
     * @return Result indicating success, failure, or deduplication
     */
    QueuePublishResult publish(QueueMessage message);

    /**
     * Publish multiple messages in a batch.
     * For SQS, batch size is limited to 10 messages.
     * For other implementations, batch may be processed sequentially.
     *
     * @param messages The messages to publish
     * @return Result indicating which messages succeeded/failed
     */
    QueuePublishResult publishBatch(List<QueueMessage> messages);

    /**
     * Get approximate queue depth (number of messages waiting).
     * This is an estimate and may not be exact for all queue types.
     *
     * @return Approximate number of messages in queue
     */
    long getQueueDepth();

    /**
     * Get the queue type this publisher is for.
     *
     * @return The queue type
     */
    QueueType getQueueType();

    /**
     * Check if this publisher is healthy and can accept messages.
     *
     * @return true if the queue connection is healthy
     */
    boolean isHealthy();

    /**
     * Close any connections. Called on shutdown.
     */
    default void close() {
        // Default no-op for implementations that don't need cleanup
    }
}
