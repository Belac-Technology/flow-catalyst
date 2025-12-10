package tech.flowcatalyst.platform.audit;

import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;

/**
 * Repository for audit log entries.
 */
@ApplicationScoped
public class AuditLogRepository implements PanacheMongoRepository<AuditLog> {

    /**
     * Find audit logs for a specific entity.
     */
    public List<AuditLog> findByEntity(String entityType, Long entityId) {
        return list("entityType = ?1 and entityId = ?2", Sort.descending("performedAt"), entityType, entityId);
    }

    /**
     * Find audit logs by principal.
     */
    public List<AuditLog> findByPrincipal(String principalId) {
        return list("principalId", Sort.descending("performedAt"), principalId);
    }

    /**
     * Find audit logs within a time range.
     */
    public List<AuditLog> findByTimeRange(Instant from, Instant to) {
        return list("performedAt >= ?1 and performedAt <= ?2", Sort.descending("performedAt"), from, to);
    }

    /**
     * Find audit logs for a specific operation type.
     */
    public List<AuditLog> findByOperation(String operation) {
        return list("operation", Sort.descending("performedAt"), operation);
    }
}
