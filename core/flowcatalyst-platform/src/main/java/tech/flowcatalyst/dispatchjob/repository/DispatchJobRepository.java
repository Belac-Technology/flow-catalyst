package tech.flowcatalyst.dispatchjob.repository;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.dispatch.DispatchMode;
import tech.flowcatalyst.dispatchjob.dto.CreateDispatchJobRequest;
import tech.flowcatalyst.dispatchjob.dto.DispatchJobFilter;
import tech.flowcatalyst.dispatchjob.entity.DispatchAttempt;
import tech.flowcatalyst.dispatchjob.entity.DispatchJob;
import tech.flowcatalyst.dispatchjob.entity.DispatchJobMetadata;
import tech.flowcatalyst.dispatchjob.model.DispatchKind;
import tech.flowcatalyst.dispatchjob.model.DispatchStatus;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.*;
import java.util.HashSet;

/**
 * MongoDB repository for DispatchJob using Panache MongoDB.
 *
 * Uses embedded documents for metadata and attempts arrays.
 */
@ApplicationScoped
public class DispatchJobRepository implements PanacheMongoRepositoryBase<DispatchJob, String> {

    /**
     * Create a new dispatch job with embedded metadata
     */
    public DispatchJob create(CreateDispatchJobRequest request) {
        DispatchJob job = new DispatchJob();
        job.id = TsidGenerator.generate();
        job.externalId = request.externalId();
        job.source = request.source();
        // Classification fields
        job.kind = request.kind() != null ? request.kind() : DispatchKind.EVENT;
        job.code = request.code();
        job.subject = request.subject();
        job.eventId = request.eventId();
        job.correlationId = request.correlationId();
        // Target information
        job.targetUrl = request.targetUrl();
        job.protocol = request.protocol() != null ?
            request.protocol() : job.protocol;
        job.headers = request.headers() != null ?
            request.headers() : new HashMap<>();
        job.payload = request.payload();
        job.payloadContentType = request.payloadContentType() != null ?
            request.payloadContentType() : job.payloadContentType;
        job.dataOnly = request.dataOnly() != null ? request.dataOnly() : true;
        job.serviceAccountId = request.serviceAccountId();

        // New context and dispatch behavior fields
        job.clientId = request.clientId();
        job.subscriptionId = request.subscriptionId();
        job.mode = request.mode() != null ? request.mode() : DispatchMode.IMMEDIATE;
        job.dispatchPoolId = request.dispatchPoolId();
        job.messageGroup = request.messageGroup();
        job.sequence = request.sequence() != null ? request.sequence() : 99;
        job.timeoutSeconds = request.timeoutSeconds() != null ? request.timeoutSeconds() : 30;
        job.schemaId = request.schemaId();

        job.maxRetries = request.maxRetries() != null ? request.maxRetries() : job.maxRetries;
        job.retryStrategy = request.retryStrategy() != null ?
            request.retryStrategy() : job.retryStrategy;
        job.scheduledFor = request.scheduledFor();
        job.expiresAt = request.expiresAt();
        job.idempotencyKey = request.idempotencyKey();
        job.status = DispatchStatus.PENDING;
        job.attemptCount = 0;
        job.createdAt = Instant.now();
        job.updatedAt = Instant.now();
        job.attempts = new ArrayList<>();

        // Convert metadata Map to embedded list
        if (request.metadata() != null) {
            job.metadata = request.metadata().entrySet().stream()
                .map(e -> {
                    DispatchJobMetadata meta = new DispatchJobMetadata(e.getKey(), e.getValue());
                    meta.id = TsidGenerator.generate();
                    return meta;
                })
                .toList();
        }

        persist(job);
        return job;
    }

    /**
     * Add delivery attempt to job
     */
    public void addAttempt(String jobId, DispatchAttempt attempt) {
        attempt.id = TsidGenerator.generate();
        attempt.createdAt = Instant.now();

        DispatchJob job = findById(jobId);
        if (job != null) {
            job.attempts.add(attempt);
            job.attemptCount++;
            job.lastAttemptAt = attempt.attemptedAt;
            job.updatedAt = Instant.now();
            update(job);
        }
    }

    /**
     * Update job status
     */
    public void updateStatus(String jobId, DispatchStatus status,
                             Instant completedAt, Long durationMillis, String lastError) {
        DispatchJob job = findById(jobId);
        if (job != null) {
            job.status = status;
            job.updatedAt = Instant.now();

            if (completedAt != null) {
                job.completedAt = completedAt;
            }
            if (durationMillis != null) {
                job.durationMillis = durationMillis;
            }
            if (lastError != null) {
                job.lastError = lastError;
            }

            update(job);
        }
    }

    /**
     * Find jobs matching filter criteria using Panache MongoDB
     */
    public List<DispatchJob> findWithFilter(DispatchJobFilter filter) {
        StringBuilder query = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        if (filter.status() != null) {
            conditions.add("status = :status");
            params.put("status", filter.status());
        }
        if (filter.source() != null) {
            conditions.add("source = :source");
            params.put("source", filter.source());
        }
        if (filter.kind() != null) {
            conditions.add("kind = :kind");
            params.put("kind", filter.kind());
        }
        if (filter.code() != null) {
            conditions.add("code = :code");
            params.put("code", filter.code());
        }
        if (filter.clientId() != null) {
            conditions.add("clientId = :clientId");
            params.put("clientId", filter.clientId());
        }
        if (filter.subscriptionId() != null) {
            conditions.add("subscriptionId = :subscriptionId");
            params.put("subscriptionId", filter.subscriptionId());
        }
        if (filter.dispatchPoolId() != null) {
            conditions.add("dispatchPoolId = :dispatchPoolId");
            params.put("dispatchPoolId", filter.dispatchPoolId());
        }
        if (filter.messageGroup() != null) {
            conditions.add("messageGroup = :messageGroup");
            params.put("messageGroup", filter.messageGroup());
        }
        if (filter.createdAfter() != null) {
            conditions.add("createdAt >= :createdAfter");
            params.put("createdAfter", filter.createdAfter());
        }
        if (filter.createdBefore() != null) {
            conditions.add("createdAt <= :createdBefore");
            params.put("createdBefore", filter.createdBefore());
        }

        if (conditions.isEmpty()) {
            return findAll().page(filter.page(), filter.size()).list();
        }

        query.append(String.join(" and ", conditions));

        return find(query.toString(), params)
            .page(filter.page(), filter.size())
            .list();
    }

    /**
     * Count jobs matching filter criteria
     */
    public long countWithFilter(DispatchJobFilter filter) {
        StringBuilder query = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();

        if (filter.status() != null) {
            conditions.add("status = :status");
            params.put("status", filter.status());
        }
        if (filter.source() != null) {
            conditions.add("source = :source");
            params.put("source", filter.source());
        }
        if (filter.kind() != null) {
            conditions.add("kind = :kind");
            params.put("kind", filter.kind());
        }
        if (filter.code() != null) {
            conditions.add("code = :code");
            params.put("code", filter.code());
        }
        if (filter.clientId() != null) {
            conditions.add("clientId = :clientId");
            params.put("clientId", filter.clientId());
        }
        if (filter.subscriptionId() != null) {
            conditions.add("subscriptionId = :subscriptionId");
            params.put("subscriptionId", filter.subscriptionId());
        }
        if (filter.dispatchPoolId() != null) {
            conditions.add("dispatchPoolId = :dispatchPoolId");
            params.put("dispatchPoolId", filter.dispatchPoolId());
        }
        if (filter.messageGroup() != null) {
            conditions.add("messageGroup = :messageGroup");
            params.put("messageGroup", filter.messageGroup());
        }
        if (filter.createdAfter() != null) {
            conditions.add("createdAt >= :createdAfter");
            params.put("createdAfter", filter.createdAfter());
        }
        if (filter.createdBefore() != null) {
            conditions.add("createdAt <= :createdBefore");
            params.put("createdBefore", filter.createdBefore());
        }

        if (conditions.isEmpty()) {
            return count();
        }

        query.append(String.join(" and ", conditions));

        return count(query.toString(), params);
    }

    /**
     * Find jobs by metadata key-value pair using MongoDB $elemMatch
     */
    public List<DispatchJob> findByMetadata(String key, String value) {
        // Use MongoDB $elemMatch for querying embedded array
        return list("metadata",
            org.bson.Document.parse("{'$elemMatch': {'key': '" + key + "', 'value': '" + value + "'}}"));
    }

    /**
     * Find jobs by multiple metadata filters (AND condition)
     */
    public List<DispatchJob> findByMetadataFilters(Map<String, String> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return List.of();
        }

        // For multiple filters, all must match (AND condition)
        // Build $and query with multiple $elemMatch conditions
        List<org.bson.Document> conditions = new ArrayList<>();
        for (Map.Entry<String, String> entry : metadataFilters.entrySet()) {
            conditions.add(new org.bson.Document("metadata",
                new org.bson.Document("$elemMatch",
                    new org.bson.Document("key", entry.getKey())
                        .append("value", entry.getValue()))));
        }

        org.bson.Document query = new org.bson.Document("$and", conditions);
        return mongoCollection().find(query).into(new ArrayList<>());
    }

    // =========================================================================
    // Scheduler Query Methods
    // =========================================================================

    /**
     * Find PENDING jobs, ordered by messageGroup, sequence, and createdAt.
     * Used by the dispatch scheduler to poll for jobs to process.
     *
     * @param limit Maximum number of jobs to return
     * @return List of pending jobs
     */
    public List<DispatchJob> findPendingJobs(int limit) {
        return find("status", Sort.by("messageGroup").and("sequence").and("createdAt"), DispatchStatus.PENDING)
            .page(0, limit)
            .list();
    }

    /**
     * Count jobs in a specific status for a message group.
     * Used to check for ERROR jobs when enforcing BLOCK_ON_ERROR mode.
     *
     * @param messageGroup The message group to check
     * @param status The status to count
     * @return Count of jobs matching the criteria
     */
    public long countByMessageGroupAndStatus(String messageGroup, DispatchStatus status) {
        return count("messageGroup = ?1 and status = ?2", messageGroup, status);
    }

    /**
     * Find all distinct message groups that have jobs in ERROR status.
     * Used to efficiently check multiple groups at once.
     *
     * @param messageGroups Set of message groups to check
     * @return Set of groups that have ERROR jobs
     */
    public Set<String> findGroupsWithErrors(Set<String> messageGroups) {
        if (messageGroups == null || messageGroups.isEmpty()) {
            return Set.of();
        }

        // Query for ERROR jobs in any of the specified groups
        org.bson.Document query = new org.bson.Document()
            .append("status", DispatchStatus.ERROR.name())
            .append("messageGroup", new org.bson.Document("$in", new ArrayList<>(messageGroups)));

        // Use aggregation to get distinct message groups
        List<String> errorGroups = mongoCollection()
            .distinct("messageGroup", query, String.class)
            .into(new ArrayList<>());

        return new HashSet<>(errorGroups);
    }
}
