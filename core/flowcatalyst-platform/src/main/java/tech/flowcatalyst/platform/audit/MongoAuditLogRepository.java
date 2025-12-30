package tech.flowcatalyst.platform.audit;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of AuditLogRepository.
 * Package-private to prevent direct injection - use AuditLogRepository interface.
 */
@ApplicationScoped
@Typed(AuditLogRepository.class)
@Instrumented(collection = "audit_logs")
class MongoAuditLogRepository implements PanacheMongoRepositoryBase<AuditLog, String>, AuditLogRepository {

    @Override
    public List<AuditLog> findByEntity(String entityType, String entityId) {
        return list("entityType = ?1 and entityId = ?2", Sort.descending("performedAt"), entityType, entityId);
    }

    @Override
    public List<AuditLog> findByPrincipal(String principalId) {
        return list("principalId", Sort.descending("performedAt"), principalId);
    }

    @Override
    public List<AuditLog> findByTimeRange(Instant from, Instant to) {
        return list("performedAt >= ?1 and performedAt <= ?2", Sort.descending("performedAt"), from, to);
    }

    @Override
    public List<AuditLog> findByOperation(String operation) {
        return list("operation", Sort.descending("performedAt"), operation);
    }

    @Override
    public List<AuditLog> findAllSorted() {
        return listAll(Sort.descending("performedAt"));
    }

    @Override
    public List<AuditLog> findPaged(int page, int pageSize) {
        return findAll(Sort.descending("performedAt"))
            .page(page, pageSize)
            .list();
    }

    @Override
    public List<AuditLog> findByEntityType(String entityType) {
        return list("entityType", Sort.descending("performedAt"), entityType);
    }

    @Override
    public List<AuditLog> findByEntityTypePaged(String entityType, int page, int pageSize) {
        return find("entityType", Sort.descending("performedAt"), entityType)
            .page(page, pageSize)
            .list();
    }

    @Override
    public long countByEntityType(String entityType) {
        return count("entityType", entityType);
    }

    @Override
    public List<String> findDistinctEntityTypes() {
        return mongoCollection()
            .distinct("entityType", String.class)
            .into(new java.util.ArrayList<>());
    }

    @Override
    public List<String> findDistinctOperations() {
        return mongoCollection()
            .distinct("operation", String.class)
            .into(new java.util.ArrayList<>());
    }

    // Delegate to Panache methods via interface
    @Override
    public AuditLog findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<AuditLog> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<AuditLog> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(AuditLog log) {
        PanacheMongoRepositoryBase.super.persist(log);
    }

    @Override
    public void update(AuditLog log) {
        PanacheMongoRepositoryBase.super.update(log);
    }

    @Override
    public void delete(AuditLog log) {
        PanacheMongoRepositoryBase.super.delete(log);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
