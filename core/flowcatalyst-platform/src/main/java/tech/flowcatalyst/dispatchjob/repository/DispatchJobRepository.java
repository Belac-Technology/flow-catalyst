package tech.flowcatalyst.dispatchjob.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import tech.flowcatalyst.dispatchjob.dto.CreateDispatchJobRequest;
import tech.flowcatalyst.dispatchjob.dto.DispatchJobFilter;
import tech.flowcatalyst.dispatchjob.entity.DispatchAttempt;
import tech.flowcatalyst.dispatchjob.entity.DispatchJob;
import tech.flowcatalyst.dispatchjob.entity.DispatchJobMetadata;
import tech.flowcatalyst.dispatchjob.model.DispatchStatus;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL repository for DispatchJob using Hibernate ORM Panache.
 *
 * Uses JSONB columns for metadata and attempts arrays for efficient
 * storage and querying while maintaining aggregate boundaries.
 */
@ApplicationScoped
public class DispatchJobRepository implements PanacheRepositoryBase<DispatchJob, Long> {

    @Inject
    EntityManager em;

    /**
     * Create a new dispatch job with embedded metadata
     */
    @Transactional
    public DispatchJob create(CreateDispatchJobRequest request) {
        DispatchJob job = new DispatchJob();
        job.id = TsidGenerator.generate();
        job.externalId = request.externalId();
        job.source = request.source();
        job.type = request.type();
        job.groupId = request.groupId();
        job.targetUrl = request.targetUrl();
        job.protocol = request.protocol() != null ?
            request.protocol() : job.protocol;
        job.headers = request.headers() != null ?
            request.headers() : new HashMap<>();
        job.payload = request.payload();
        job.payloadContentType = request.payloadContentType() != null ?
            request.payloadContentType() : job.payloadContentType;
        job.credentialsId = request.credentialsId();
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

        persist(job);  // Panache handles POJO serialization
        return job;
    }

    /**
     * Add delivery attempt to job
     */
    @Transactional
    public void addAttempt(Long jobId, DispatchAttempt attempt) {
        attempt.id = TsidGenerator.generate();
        attempt.createdAt = Instant.now();

        DispatchJob job = findById(jobId);
        if (job != null) {
            job.attempts.add(attempt);
            job.attemptCount++;
            job.lastAttemptAt = attempt.attemptedAt;
            job.updatedAt = Instant.now();
            persist(job);
        }
    }

    /**
     * Update job status
     */
    @Transactional
    public void updateStatus(Long jobId, DispatchStatus status,
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

            persist(job);
        }
    }

    /**
     * Find jobs matching filter criteria using Panache
     */
    public List<DispatchJob> findWithFilter(DispatchJobFilter filter) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filter.status() != null) {
            query.append(" AND status = :status");
            params.put("status", filter.status());
        }
        if (filter.source() != null) {
            query.append(" AND source = :source");
            params.put("source", filter.source());
        }
        if (filter.type() != null) {
            query.append(" AND type = :type");
            params.put("type", filter.type());
        }
        if (filter.groupId() != null) {
            query.append(" AND groupId = :groupId");
            params.put("groupId", filter.groupId());
        }
        if (filter.createdAfter() != null) {
            query.append(" AND createdAt >= :createdAfter");
            params.put("createdAfter", filter.createdAfter());
        }
        if (filter.createdBefore() != null) {
            query.append(" AND createdAt <= :createdBefore");
            params.put("createdBefore", filter.createdBefore());
        }

        return find(query.toString(), params)
            .page(filter.page(), filter.size())
            .list();
    }

    /**
     * Count jobs matching filter criteria
     */
    public long countWithFilter(DispatchJobFilter filter) {
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filter.status() != null) {
            query.append(" AND status = :status");
            params.put("status", filter.status());
        }
        if (filter.source() != null) {
            query.append(" AND source = :source");
            params.put("source", filter.source());
        }
        if (filter.type() != null) {
            query.append(" AND type = :type");
            params.put("type", filter.type());
        }
        if (filter.groupId() != null) {
            query.append(" AND groupId = :groupId");
            params.put("groupId", filter.groupId());
        }
        if (filter.createdAfter() != null) {
            query.append(" AND createdAt >= :createdAfter");
            params.put("createdAfter", filter.createdAfter());
        }
        if (filter.createdBefore() != null) {
            query.append(" AND createdAt <= :createdBefore");
            params.put("createdBefore", filter.createdBefore());
        }

        return count(query.toString(), params);
    }

    /**
     * Find jobs by metadata key-value pair using PostgreSQL JSONB operators
     */
    public List<DispatchJob> findByMetadata(String key, String value) {
        String query = "SELECT j FROM DispatchJob j, jsonb_array_elements(j.metadata) AS m " +
                       "WHERE m->>'key' = :key AND m->>'value' = :value";
        return em.createQuery(query, DispatchJob.class)
            .setParameter("key", key)
            .setParameter("value", value)
            .getResultList();
    }

    /**
     * Find jobs by multiple metadata filters (AND condition)
     */
    public List<DispatchJob> findByMetadataFilters(Map<String, String> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return List.of();
        }

        // Build native query for JSONB containment check
        StringBuilder queryBuilder = new StringBuilder(
            "SELECT DISTINCT j.* FROM dispatch_jobs j, jsonb_array_elements(j.metadata) AS m WHERE ");

        List<String> conditions = new ArrayList<>();
        metadataFilters.forEach((key, value) -> {
            conditions.add(String.format(
                "EXISTS (SELECT 1 FROM jsonb_array_elements(j.metadata) AS m2 " +
                "WHERE m2->>'key' = '%s' AND m2->>'value' = '%s')",
                key.replace("'", "''"), value.replace("'", "''")));
        });

        queryBuilder.append(String.join(" AND ", conditions));

        return em.createNativeQuery(queryBuilder.toString(), DispatchJob.class)
            .getResultList();
    }
}
