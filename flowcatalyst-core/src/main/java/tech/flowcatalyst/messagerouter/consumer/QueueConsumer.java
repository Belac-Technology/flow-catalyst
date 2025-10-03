package tech.flowcatalyst.messagerouter.consumer;

public interface QueueConsumer {

    /**
     * Starts the queue consumer and begins polling/consuming messages
     */
    void start();

    /**
     * Stops the queue consumer from accepting new messages
     */
    void stop();

    /**
     * Returns the queue identifier (name or URI)
     */
    String getQueueIdentifier();
}
