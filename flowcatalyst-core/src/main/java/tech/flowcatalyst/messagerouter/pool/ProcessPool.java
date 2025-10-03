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
     * Submits a message to the process pool's blocking queue
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
}
