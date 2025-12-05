package tech.flowcatalyst.postbox.handler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.postbox.model.MessageType;

/**
 * Factory for selecting the appropriate message handler based on message type
 */
@ApplicationScoped
public class MessageHandlerFactory {

    @Inject
    EventMessageHandler eventHandler;

    @Inject
    DispatchJobMessageHandler dispatchJobHandler;

    /**
     * Get the handler for a given message type
     * @param type the message type
     * @return the appropriate handler
     * @throws IllegalArgumentException if type is not recognized
     */
    public MessageHandler getHandler(MessageType type) {
        return switch (type) {
            case EVENT -> eventHandler;
            case DISPATCH_JOB -> dispatchJobHandler;
        };
    }

}
