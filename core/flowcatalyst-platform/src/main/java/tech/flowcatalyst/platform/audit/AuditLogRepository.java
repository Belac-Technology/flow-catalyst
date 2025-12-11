package tech.flowcatalyst.platform.audit;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;

/**
 * Repository for audit log entries.
 */
@ApplicationScoped
public class AuditLogRepository implements PanacheMongoRepositoryBase<AuditLog, String> {

    /**
     * Find audit logs for a specific entity.
     */
    public List<AuditLog> findByEntity(String entityType, String entityId) {
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

    /**
     * Find all audit logs, sorted by most recent first.
     */
    public List<AuditLog> findAllSorted() {
        return listAll(Sort.descending("performedAt"));
    }

    /**
     * Find audit logs with pagination, sorted by most recent first.
     */
    public List<AuditLog> findPaged(int page, int pageSize) {
        return findAll(Sort.descending("performedAt"))
            .page(page, pageSize)
            .list();
    }

    /**
     * Find audit logs for a specific entity type.
     */
    public List<AuditLog> findByEntityType(String entityType) {
        return list("entityType", Sort.descending("performedAt"), entityType);
    }

    /**
     * Find audit logs for a specific entity type with pagination.
     */
    public List<AuditLog> findByEntityTypePaged(String entityType, int page, int pageSize) {
        return find("entityType", Sort.descending("performedAt"), entityType)
            .page(page, pageSize)
            .list();
    }

    /**
     * Count audit logs for a specific entity type.
     */
    public long countByEntityType(String entityType) {
        return count("entityType", entityType);
    }
}
