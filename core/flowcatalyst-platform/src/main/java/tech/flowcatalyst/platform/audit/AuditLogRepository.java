package tech.flowcatalyst.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for AuditLog entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface AuditLogRepository {

    // Read operations
    AuditLog findById(String id);
    Optional<AuditLog> findByIdOptional(String id);
    List<AuditLog> findByEntity(String entityType, String entityId);
    List<AuditLog> findByPrincipal(String principalId);
    List<AuditLog> findByTimeRange(Instant from, Instant to);
    List<AuditLog> findByOperation(String operation);
    List<AuditLog> findAllSorted();
    List<AuditLog> findPaged(int page, int pageSize);
    List<AuditLog> findByEntityType(String entityType);
    List<AuditLog> findByEntityTypePaged(String entityType, int page, int pageSize);
    List<AuditLog> listAll();
    long count();
    long countByEntityType(String entityType);

    // Aggregation operations
    List<String> findDistinctEntityTypes();
    List<String> findDistinctOperations();

    // Write operations
    void persist(AuditLog log);
    void update(AuditLog log);
    void delete(AuditLog log);
    boolean deleteById(String id);
}
