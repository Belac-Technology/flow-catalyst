package tech.flowcatalyst.platform.audit;

import java.util.List;

/**
 * Repository interface for AuditLog entities.
 * Provides audit log access methods for compliance and debugging.
 */
public interface AuditLogRepository {

    // Read operations
    AuditLog findById(String id);
    List<AuditLog> findByEntity(String entityType, String entityId);
    List<AuditLog> findByPrincipal(String principalId);
    List<AuditLog> findByOperation(String operation);
    List<AuditLog> findPaged(int page, int pageSize);
    List<AuditLog> findByEntityTypePaged(String entityType, int page, int pageSize);
    long count();
    long countByEntityType(String entityType);

    // Aggregation operations
    List<String> findDistinctEntityTypes();
    List<String> findDistinctOperations();

    // Write operations
    void persist(AuditLog log);
}
