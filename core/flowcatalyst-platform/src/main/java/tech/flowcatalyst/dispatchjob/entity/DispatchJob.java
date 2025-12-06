package tech.flowcatalyst.dispatchjob.entity;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import tech.flowcatalyst.dispatchjob.model.DispatchProtocol;
import tech.flowcatalyst.dispatchjob.model.DispatchStatus;
import java.time.Instant;
import java.util.*;

/**
 * Dispatch Job aggregate root stored in MongoDB.
 *
 * Document structure:
 * - metadata: embedded array for flexible key-value pairs
 * - attempts: embedded array for delivery history
 * - credentialsId: reference to DispatchCredentials collection
 *
 * Benefits of document storage:
 * - Single document read for complete aggregate
 * - Efficient queries on metadata using MongoDB operators
 * - Natural aggregate boundary maintained
 */
@MongoEntity(collection = "dispatch_jobs")
public class DispatchJob extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    public String externalId;

    // Source & Classification
    public String source;

    public String type;

    public String groupId;

    // Metadata stored as embedded array
    public List<DispatchJobMetadata> metadata = new ArrayList<>();

    // Target Information
    public String targetUrl;

    public DispatchProtocol protocol = DispatchProtocol.HTTP_WEBHOOK;

    public Map<String, String> headers = new HashMap<>();

    // Payload
    public String payload;

    public String payloadContentType = "application/json";

    // Credentials Reference (ID only, not embedded)
    public Long credentialsId;

    // Execution Control
    public DispatchStatus status = DispatchStatus.PENDING;

    public Integer maxRetries = 3;

    public String retryStrategy = "exponential";

    public Instant scheduledFor;

    public Instant expiresAt;

    // Tracking & Observability
    public Integer attemptCount = 0;

    public Instant lastAttemptAt;

    public Instant completedAt;

    public Long durationMillis;

    public String lastError;

    // Idempotency
    public String idempotencyKey;

    // Attempts stored as embedded array
    public List<DispatchAttempt> attempts = new ArrayList<>();

    // Timestamps
    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

    public DispatchJob() {
    }
}
