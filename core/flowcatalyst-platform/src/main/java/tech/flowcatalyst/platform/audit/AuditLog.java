package tech.flowcatalyst.platform.audit;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * Audit log entry tracking operations performed on entities.
 *
 * Generic audit log that can track any entity type and operation.
 * Stores the full operation payload as JSON for complete audit trail.
 */
@MongoEntity(collection = "audit_logs")
public class AuditLog extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    /**
     * The type of entity (e.g., "EventType", "Tenant").
     */
    public String entityType;

    /**
     * The entity's TSID.
     */
    public Long entityId;

    /**
     * The operation name (e.g., "CreateEventType", "AddSchema").
     */
    public String operation;

    /**
     * The full operation record serialized as JSON.
     */
    public String operationJson;

    /**
     * The principal who performed the operation.
     */
    public Long principalId;

    /**
     * When the operation was performed.
     */
    public Instant performedAt = Instant.now();

    public AuditLog() {
    }
}
