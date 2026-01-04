package tech.flowcatalyst.platform.audit;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of AuditLogRepository.
 * Package-private to prevent direct injection - use AuditLogRepository interface.
 */
@ApplicationScoped
@Typed(AuditLogRepository.class)
class MongoAuditLogRepository implements AuditLogRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<AuditLog> collection() {
        return mongoClient.getDatabase(database).getCollection("audit_logs", AuditLog.class);
    }

    @Override
    public AuditLog findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public List<AuditLog> findByEntity(String entityType, String entityId) {
        return collection().find(and(eq("entityType", entityType), eq("entityId", entityId)))
            .sort(Sorts.descending("performedAt"))
            .into(new ArrayList<>());
    }

    @Override
    public List<AuditLog> findByPrincipal(String principalId) {
        return collection().find(eq("principalId", principalId))
            .sort(Sorts.descending("performedAt"))
            .into(new ArrayList<>());
    }

    @Override
    public List<AuditLog> findByOperation(String operation) {
        return collection().find(eq("operation", operation))
            .sort(Sorts.descending("performedAt"))
            .into(new ArrayList<>());
    }

    @Override
    public List<AuditLog> findPaged(int page, int pageSize) {
        return collection().find()
            .sort(Sorts.descending("performedAt"))
            .skip(page * pageSize)
            .limit(pageSize)
            .into(new ArrayList<>());
    }

    @Override
    public List<AuditLog> findByEntityTypePaged(String entityType, int page, int pageSize) {
        return collection().find(eq("entityType", entityType))
            .sort(Sorts.descending("performedAt"))
            .skip(page * pageSize)
            .limit(pageSize)
            .into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public long countByEntityType(String entityType) {
        return collection().countDocuments(eq("entityType", entityType));
    }

    @Override
    public List<String> findDistinctEntityTypes() {
        return collection().distinct("entityType", String.class)
            .into(new ArrayList<>());
    }

    @Override
    public List<String> findDistinctOperations() {
        return collection().distinct("operation", String.class)
            .into(new ArrayList<>());
    }

    @Override
    public void persist(AuditLog log) {
        collection().insertOne(log);
    }
}
