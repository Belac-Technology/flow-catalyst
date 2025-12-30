package tech.flowcatalyst.dispatchjob.repository;

import tech.flowcatalyst.dispatchjob.dto.CreateDispatchJobRequest;
import tech.flowcatalyst.dispatchjob.dto.DispatchJobFilter;
import tech.flowcatalyst.dispatchjob.entity.DispatchAttempt;
import tech.flowcatalyst.dispatchjob.entity.DispatchJob;
import tech.flowcatalyst.dispatchjob.model.DispatchStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Repository interface for DispatchJob entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 *
 * Uses embedded documents for metadata and attempts arrays.
 */
public interface DispatchJobRepository {

    // Read operations
    DispatchJob findById(String id);
    Optional<DispatchJob> findByIdOptional(String id);
    List<DispatchJob> findWithFilter(DispatchJobFilter filter);
    List<DispatchJob> findByMetadata(String key, String value);
    List<DispatchJob> findByMetadataFilters(Map<String, String> metadataFilters);
    List<DispatchJob> findRecentPaged(int page, int size);
    List<DispatchJob> listAll();
    long count();
    long countWithFilter(DispatchJobFilter filter);

    // Scheduler query methods
    List<DispatchJob> findPendingJobs(int limit);
    long countByMessageGroupAndStatus(String messageGroup, DispatchStatus status);
    Set<String> findGroupsWithErrors(Set<String> messageGroups);

    // Stale job queries
    List<DispatchJob> findStaleQueued(Instant threshold);
    List<DispatchJob> findStaleQueued(Instant threshold, int limit);

    // Write operations
    DispatchJob create(CreateDispatchJobRequest request);
    void addAttempt(String jobId, DispatchAttempt attempt);
    void updateStatus(String jobId, DispatchStatus status, Instant completedAt, Long durationMillis, String lastError);
    void updateStatusBatch(List<String> ids, DispatchStatus status);
    void persist(DispatchJob job);
    void persistAll(List<DispatchJob> jobs);
    void update(DispatchJob job);
    void delete(DispatchJob job);
    boolean deleteById(String id);
}
