package tech.flowcatalyst.postbox.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.postbox.entity.PostboxMessage;
import tech.flowcatalyst.postbox.model.MessageStatus;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class PostboxMessageRepository implements PanacheRepository<PostboxMessage> {

    public List<PostboxMessage> findPendingMessages(Long tenantId, String partitionId, Integer limit) {
        return find("tenantId = ?1 and partitionId = ?2 and status = ?3 order by payloadSize, createdAt",
                tenantId, partitionId, MessageStatus.PENDING)
                .page(0, limit)
                .list();
    }

    public List<Object[]> findActivePartitions(Instant since) {
        return find("select distinct tenantId, partitionId from PostboxMessage where createdAt > ?1", since)
                .project(Object[].class)
                .list();
    }

    public void updateStatus(String messageId, MessageStatus status, Instant processedAt) {
        update("status = ?1, processedAt = ?2 where id = ?3", status, processedAt, messageId);
    }

    public void updateStatusWithError(String messageId, MessageStatus status, Instant processedAt, String errorReason) {
        update("status = ?1, processedAt = ?2, errorReason = ?3 where id = ?4", status, processedAt, errorReason, messageId);
    }

    public void incrementRetryCount(String messageId) {
        update("retryCount = retryCount + 1 where id = ?1", messageId);
    }

    public void updateRetryCountAndError(String messageId, String errorReason) {
        update("retryCount = retryCount + 1, errorReason = ?1 where id = ?2", errorReason, messageId);
    }

}
