package tech.flowcatalyst.messagerouter.pool;

import tech.flowcatalyst.messagerouter.model.MessagePointer;

public interface ProcessPool {

    /**
     * Starts the process pool workers
     */
    void start();

    /**
     * Stops accepting new messages and waits for in-flight messages to drain
     */
    void drain();

    /**
     * Submits a message to the process pool's blocking queue.
     * Queue capacity is calculated as max(concurrency × 10, 500).
     *
     * @param message the message to process
     * @return true if message was accepted, false if queue is full
     */
    boolean submit(MessagePointer message);

    /**
     * Returns the pool code identifier
     */
    String getPoolCode();

    /**
     * Returns the configured concurrency level
     */
    int getConcurrency();

    /**
     * Returns the configured rate limit per minute (null if not configured)
     */
    Integer getRateLimitPerMinute();

    /**
     * Returns true if the pool has fully drained (queue empty and all workers idle)
     */
    boolean isFullyDrained();

    /**
     * Shuts down the pool executor service after draining completes
     */
    void shutdown();

    /**
     * Returns the current queue size
     */
    int getQueueSize();

    /**
     * Returns the number of active workers currently processing messages
     */
    int getActiveWorkers();

    /**
     * Returns the total queue capacity for this pool
     */
    int getQueueCapacity();

    /**
     * Checks if the pool is currently rate limited
     * @return true if rate limited, false otherwise
     */
    boolean isRateLimited();
}
