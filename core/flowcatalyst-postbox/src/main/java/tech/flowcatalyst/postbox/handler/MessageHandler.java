package tech.flowcatalyst.postbox.handler;

import tech.flowcatalyst.postbox.entity.PostboxMessage;

/**
 * Handler abstraction for processing postbox messages
 * Implementations handle different message types (EVENT, DISPATCH_JOB)
 */
public interface MessageHandler {

    /**
     * Process a postbox message
     * @param message the message to process
     * @throws Exception if processing fails
     */
    void handle(PostboxMessage message) throws Exception;

}
