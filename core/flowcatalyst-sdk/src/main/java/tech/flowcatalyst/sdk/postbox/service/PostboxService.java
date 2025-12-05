package tech.flowcatalyst.postbox.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.flowcatalyst.postbox.dto.PostboxPayload;
import tech.flowcatalyst.postbox.entity.PostboxMessage;
import tech.flowcatalyst.postbox.model.MessageStatus;
import tech.flowcatalyst.postbox.model.MessageType;
import tech.flowcatalyst.postbox.repository.PostboxMessageRepository;

@ApplicationScoped
public class PostboxService {

    @Inject
    PostboxMessageRepository repository;

    @ConfigProperty(name = "postbox.payload.max-size-bytes", defaultValue = "10485760")
    Long maxSizeBytes;

    @Transactional
    public PostboxMessage ingestMessage(PostboxPayload payload) {
        // Validate payload size
        if (payload.payload == null || payload.payload.isEmpty()) {
            throw new IllegalArgumentException("Payload cannot be empty");
        }

        long payloadSize = payload.payload.getBytes().length;

        if (payloadSize > maxSizeBytes) {
            throw new IllegalArgumentException("Payload size " + payloadSize + " exceeds maximum allowed size " + maxSizeBytes);
        }

        // Create and persist message
        PostboxMessage message = new PostboxMessage();
        message.id = payload.id;
        message.tenantId = payload.tenantId;
        message.partitionId = payload.partitionId;
        message.type = MessageType.valueOf(payload.type);
        message.payload = payload.payload;
        message.payloadSize = payloadSize;
        message.status = MessageStatus.PENDING;
        message.headers = payload.headers;

        message.persist();
        return message;
    }

}
