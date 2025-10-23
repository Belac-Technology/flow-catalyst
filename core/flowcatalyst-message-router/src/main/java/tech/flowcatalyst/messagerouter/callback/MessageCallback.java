package tech.flowcatalyst.messagerouter.callback;

import tech.flowcatalyst.messagerouter.model.MessagePointer;

/**
 * Callback interface for queue consumers to notify about message acknowledgment
 */
public interface MessageCallback {

    /**
     * Acknowledge successful processing of a message
     *
     * @param message the message to acknowledge
     */
    void ack(MessagePointer message);

    /**
     * Negative acknowledge a message (requeue or DLQ)
     *
     * @param message the message to nack
     */
    void nack(MessagePointer message);
}
