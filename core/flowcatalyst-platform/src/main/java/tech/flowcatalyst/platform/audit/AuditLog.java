package tech.flowcatalyst.platform.audit;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * Audit log entry tracking operations performed on entities.
 *
 * Generic audit log that can track any entity type and operation.
 * Stores the full operation payload as JSON for complete audit trail.
 */
@Entity
@Table(name = "audit_logs",
    indexes = {
        @Index(name = "idx_audit_log_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_log_principal", columnList = "principal_id"),
        @Index(name = "idx_audit_log_performed_at", columnList = "performed_at")
    }
)
public class AuditLog extends PanacheEntityBase {

    @Id
    public Long id;

    /**
     * The type of entity (e.g., "EventType", "Tenant").
     */
    @Column(name = "entity_type", nullable = false, length = 100)
    public String entityType;

    /**
     * The entity's TSID.
     */
    @Column(name = "entity_id", nullable = false)
    public Long entityId;

    /**
     * The operation name (e.g., "CreateEventType", "AddSchema").
     */
    @Column(name = "operation", nullable = false, length = 50)
    public String operation;

    /**
     * The full operation record serialized as JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operation_json", columnDefinition = "jsonb", nullable = false)
    public String operationJson;

    /**
     * The principal who performed the operation.
     */
    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    /**
     * When the operation was performed.
     */
    @Column(name = "performed_at", nullable = false)
    public Instant performedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
        if (performedAt == null) {
            performedAt = Instant.now();
        }
    }

    public AuditLog() {
    }
}
