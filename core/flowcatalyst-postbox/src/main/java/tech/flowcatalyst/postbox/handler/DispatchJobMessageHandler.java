package tech.flowcatalyst.postbox.handler;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import tech.flowcatalyst.postbox.entity.PostboxMessage;
import tech.flowcatalyst.postbox.model.MessageType;

/**
 * Handler for DISPATCH_JOB type messages
 * Routes dispatch jobs to the message router for delivery
 * Placeholder for Phase 3 - will integrate with dispatch job infrastructure
 */
@ApplicationScoped
public class DispatchJobMessageHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(DispatchJobMessageHandler.class);

    @Override
    public void handle(PostboxMessage message) throws Exception {
        if (message.type != MessageType.DISPATCH_JOB) {
            throw new IllegalArgumentException("DispatchJobMessageHandler can only handle DISPATCH_JOB type messages");
        }

        log.debugf("Processing dispatch job message: id=%s, tenant=%d, partition=%s",
                message.id, message.tenantId, message.partitionId);

        try {
            // TODO: Phase 3 - Send to message router or dispatch job processor
            // The payload contains the dispatch job details (webhook URL, payload, etc.)

            log.infof("Dispatch job processed: id=%s, tenant=%d",
                    message.id, message.tenantId);

        } catch (Exception e) {
            log.errorf(e, "Failed to process dispatch job message: %s", message.id);
            throw e;
        }
    }

}
