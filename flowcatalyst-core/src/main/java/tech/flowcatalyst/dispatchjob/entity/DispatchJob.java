package tech.flowcatalyst.dispatchjob.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tech.flowcatalyst.dispatchjob.model.DispatchProtocol;
import tech.flowcatalyst.dispatchjob.model.DispatchStatus;
import tech.flowcatalyst.dispatchjob.util.TsidGenerator;

import java.time.Instant;
import java.util.*;

/**
 * Dispatch Job aggregate root stored in PostgreSQL.
 *
 * Table structure:
 * - metadata: stored as JSONB for flexible key-value pairs
 * - attempts: stored as JSONB array for delivery history
 * - credentialsId: reference to DispatchCredentials table
 *
 * Benefits of JSON storage:
 * - Single row read for complete aggregate
 * - Efficient queries on metadata using PostgreSQL JSONB operators
 * - Natural aggregate boundary maintained
 */
@Entity
@Table(name = "dispatch_jobs",
    indexes = {
        @Index(name = "idx_dispatch_job_status", columnList = "status"),
        @Index(name = "idx_dispatch_job_source", columnList = "source"),
        @Index(name = "idx_dispatch_job_external_id", columnList = "external_id"),
        @Index(name = "idx_dispatch_job_idempotency_key", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_dispatch_job_scheduled", columnList = "scheduled_for"),
        @Index(name = "idx_dispatch_job_credentials", columnList = "credentials_id")
    }
)
public class DispatchJob extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "external_id", length = 100)
    public String externalId;

    // Source & Classification
    @Column(name = "source", nullable = false, length = 100)
    public String source;

    @Column(name = "type", length = 100)
    public String type;

    @Column(name = "group_id", length = 100)
    public String groupId;

    // Metadata stored as JSONB
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    public List<DispatchJobMetadata> metadata = new ArrayList<>();

    // Target Information
    @Column(name = "target_url", nullable = false, length = 2048)
    public String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false, length = 50)
    public DispatchProtocol protocol = DispatchProtocol.HTTP_WEBHOOK;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", columnDefinition = "jsonb")
    public Map<String, String> headers = new HashMap<>();

    // Payload
    @Column(name = "payload", columnDefinition = "text")
    public String payload;

    @Column(name = "payload_content_type", length = 100)
    public String payloadContentType = "application/json";

    // Credentials Reference (ID only, not embedded)
    @Column(name = "credentials_id")
    public Long credentialsId;

    // Execution Control
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    public DispatchStatus status = DispatchStatus.PENDING;

    @Column(name = "max_retries")
    public Integer maxRetries = 3;

    @Column(name = "retry_strategy", length = 50)
    public String retryStrategy = "exponential";

    @Column(name = "scheduled_for")
    public Instant scheduledFor;

    @Column(name = "expires_at")
    public Instant expiresAt;

    // Tracking & Observability
    @Column(name = "attempt_count")
    public Integer attemptCount = 0;

    @Column(name = "last_attempt_at")
    public Instant lastAttemptAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Column(name = "duration_millis")
    public Long durationMillis;

    @Column(name = "last_error", columnDefinition = "text")
    public String lastError;

    // Idempotency
    @Column(name = "idempotency_key", unique = true, length = 255)
    public String idempotencyKey;

    // Attempts stored as JSONB array
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attempts", columnDefinition = "jsonb")
    public List<DispatchAttempt> attempts = new ArrayList<>();

    // Timestamps
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public DispatchJob() {
    }
}
