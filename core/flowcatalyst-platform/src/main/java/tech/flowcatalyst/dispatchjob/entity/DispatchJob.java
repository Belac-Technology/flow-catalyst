package tech.flowcatalyst.dispatchjob.entity;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import tech.flowcatalyst.dispatch.DispatchMode;
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
    public String id;

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
    public String credentialsId;

    // Context - Client and Subscription
    /** Client this job belongs to (nullable - null means anchor-level) */
    public String clientId;

    /** Subscription that created this job (nullable - jobs can be created directly) */
    public String subscriptionId;

    // Dispatch Behavior
    /** Processing mode (IMMEDIATE, NEXT_ON_ERROR, BLOCK_ON_ERROR) */
    public DispatchMode mode = DispatchMode.IMMEDIATE;

    /** Dispatch pool for rate limiting */
    public String dispatchPoolId;

    /** Message group for FIFO ordering (e.g., subscriptionName:eventMessageGroup) */
    public String messageGroup;

    /** Sequence number for ordering within message group (default 99) */
    public int sequence = 99;

    /** Timeout in seconds for target to respond */
    public int timeoutSeconds = 30;

    // Schema Reference
    /** Optional schema ID for payload validation (not tied to eventType) */
    public String schemaId;

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
