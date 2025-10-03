package tech.flowcatalyst.messagerouter.config;

/**
 * SQS consumer mode configuration.
 * <p>
 * SYNC: Traditional blocking polls - thread waits for full long-poll duration
 * ASYNC: Non-blocking CompletableFuture-based polls - processes messages immediately as they arrive
 */
public enum SqsConsumerMode {
    /**
     * Synchronous blocking consumer.
     * Thread blocks during long poll, processes batch after poll completes.
     * Higher latency but simpler implementation.
     */
    SYNC,

    /**
     * Asynchronous non-blocking consumer.
     * Uses CompletableFuture chains to process messages as they arrive.
     * Lower latency (~20x faster) for time-sensitive workloads.
     */
    ASYNC
}
