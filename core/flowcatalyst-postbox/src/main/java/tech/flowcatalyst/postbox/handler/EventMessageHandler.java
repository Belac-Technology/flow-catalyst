package tech.flowcatalyst.postbox.handler;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import tech.flowcatalyst.postbox.entity.PostboxMessage;
import tech.flowcatalyst.postbox.model.MessageType;

/**
 * Handler for EVENT type messages
 * Routes events to appropriate domain handlers
 * Placeholder for Phase 3 - will integrate with actual event system
 */
@ApplicationScoped
public class EventMessageHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(EventMessageHandler.class);

    @Override
    public void handle(PostboxMessage message) throws Exception {
        if (message.type != MessageType.EVENT) {
            throw new IllegalArgumentException("EventMessageHandler can only handle EVENT type messages");
        }

        log.debugf("Processing event message: id=%s, tenant=%d, partition=%s",
                message.id, message.tenantId, message.partitionId);

        try {
            // Extract event type from headers or payload
            String eventType = extractEventType(message);

            // TODO: Phase 3 - Route to actual event handlers based on eventType
            // For now, just log the event
            log.infof("Event processed: id=%s, type=%s, tenant=%d",
                    message.id, eventType, message.tenantId);

        } catch (Exception e) {
            log.errorf(e, "Failed to process event message: %s", message.id);
            throw e;
        }
    }

    /**
     * Extract event type from message headers or payload
     * Currently looks in headers.event_type, fallback to payload parsing
     */
    private String extractEventType(PostboxMessage message) {
        if (message.headers != null && message.headers.containsKey("event_type")) {
            return message.headers.get("event_type").toString();
        }
        // TODO: Parse from payload if needed
        return "UNKNOWN";
    }

}
