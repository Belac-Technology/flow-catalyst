package tech.flowcatalyst.platform.audit;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;

/**
 * Repository for audit log entries.
 */
@ApplicationScoped
public class AuditLogRepository implements PanacheRepository<AuditLog> {

    /**
     * Find audit logs for a specific entity.
     */
    public List<AuditLog> findByEntity(String entityType, Long entityId) {
        return list("entityType = ?1 AND entityId = ?2 ORDER BY performedAt DESC", entityType, entityId);
    }

    /**
     * Find audit logs by principal.
     */
    public List<AuditLog> findByPrincipal(Long principalId) {
        return list("principalId = ?1 ORDER BY performedAt DESC", principalId);
    }

    /**
     * Find audit logs within a time range.
     */
    public List<AuditLog> findByTimeRange(Instant from, Instant to) {
        return list("performedAt >= ?1 AND performedAt <= ?2 ORDER BY performedAt DESC", from, to);
    }

    /**
     * Find audit logs for a specific operation type.
     */
    public List<AuditLog> findByOperation(String operation) {
        return list("operation = ?1 ORDER BY performedAt DESC", operation);
    }
}
