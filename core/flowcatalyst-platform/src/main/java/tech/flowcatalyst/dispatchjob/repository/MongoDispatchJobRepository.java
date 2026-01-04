package tech.flowcatalyst.dispatchjob.repository;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * MongoDB implementation of DispatchJobRepository.
 * Package-private to prevent direct injection - use DispatchJobRepository interface.
 *
 * Uses embedded documents for metadata and attempts arrays.
 */
@ApplicationScoped
@Typed(DispatchJobRepository.class)
class MongoDispatchJobRepository implements DispatchJobRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<DispatchJob> collection() {
        return mongoClient.getDatabase(database).getCollection("dispatch_jobs", DispatchJob.class);
    }

    private MongoCollection<Document> documentCollection() {
        return mongoClient.getDatabase(database).getCollection("dispatch_jobs");
    }

    @Override
    public DispatchJob create(CreateDispatchJobRequest request) {
        DispatchJob job = new DispatchJob();
        job.id = TsidGenerator.generate();
        job.externalId = request.externalId();
        job.source = request.source();
        job.kind = request.kind() != null ? request.kind() : DispatchKind.EVENT;
        job.code = request.code();
        job.subject = request.subject();
        job.eventId = request.eventId();
        job.correlationId = request.correlationId();
        job.targetUrl = request.targetUrl();
        job.protocol = request.protocol() != null ? request.protocol() : job.protocol;
        job.headers = request.headers() != null ? request.headers() : new HashMap<>();
        job.payload = request.payload();
        job.payloadContentType = request.payloadContentType() != null ? request.payloadContentType() : job.payloadContentType;
        job.dataOnly = request.dataOnly() != null ? request.dataOnly() : true;
        job.serviceAccountId = request.serviceAccountId();

        job.clientId = request.clientId();
        job.subscriptionId = request.subscriptionId();
        job.mode = request.mode() != null ? request.mode() : DispatchMode.IMMEDIATE;
        job.dispatchPoolId = request.dispatchPoolId();
        job.messageGroup = request.messageGroup();
        job.sequence = request.sequence() != null ? request.sequence() : 99;
        job.timeoutSeconds = request.timeoutSeconds() != null ? request.timeoutSeconds() : 30;
        job.schemaId = request.schemaId();

        job.maxRetries = request.maxRetries() != null ? request.maxRetries() : job.maxRetries;
        job.retryStrategy = request.retryStrategy() != null ? request.retryStrategy() : job.retryStrategy;
        job.scheduledFor = request.scheduledFor();
        job.expiresAt = request.expiresAt();
        job.idempotencyKey = request.idempotencyKey();
        job.status = DispatchStatus.QUEUED;
        job.attemptCount = 0;
        job.createdAt = Instant.now();
        job.updatedAt = Instant.now();
        job.attempts = new ArrayList<>();

        if (request.metadata() != null) {
            job.metadata = request.metadata().entrySet().stream()
                .map(e -> {
                    DispatchJobMetadata meta = new DispatchJobMetadata(e.getKey(), e.getValue());
                    meta.id = TsidGenerator.generate();
                    return meta;
                })
                .toList();
        }

        collection().insertOne(job);
        return job;
    }

    @Override
    public void addAttempt(String jobId, DispatchAttempt attempt) {
        attempt.id = TsidGenerator.generate();
        attempt.createdAt = Instant.now();

        collection().updateOne(
            eq("_id", jobId),
            combine(
                push("attempts", attempt),
                inc("attemptCount", 1),
                set("lastAttemptAt", attempt.attemptedAt),
                set("updatedAt", Instant.now())
            )
        );
    }

    @Override
    public void updateStatus(String jobId, DispatchStatus status,
                             Instant completedAt, Long durationMillis, String lastError) {
        List<Bson> updates = new ArrayList<>();
        updates.add(set("status", status));
        updates.add(set("updatedAt", Instant.now()));

        if (completedAt != null) {
            updates.add(set("completedAt", completedAt));
        }
        if (durationMillis != null) {
            updates.add(set("durationMillis", durationMillis));
        }
        if (lastError != null) {
            updates.add(set("lastError", lastError));
        }

        collection().updateOne(eq("_id", jobId), combine(updates));
    }

    @Override
    public List<DispatchJob> findWithFilter(DispatchJobFilter filter) {
        Bson query = buildFilter(filter);

        return collection().find(query)
            .skip(filter.page() * filter.size())
            .limit(filter.size())
            .into(new ArrayList<>());
    }

    @Override
    public long countWithFilter(DispatchJobFilter filter) {
        Bson query = buildFilter(filter);
        return collection().countDocuments(query);
    }

    private Bson buildFilter(DispatchJobFilter filter) {
        List<Bson> conditions = new ArrayList<>();

        if (filter.status() != null) {
            conditions.add(eq("status", filter.status()));
        }
        if (filter.source() != null) {
            conditions.add(eq("source", filter.source()));
        }
        if (filter.kind() != null) {
            conditions.add(eq("kind", filter.kind()));
        }
        if (filter.code() != null) {
            conditions.add(eq("code", filter.code()));
        }
        if (filter.clientId() != null) {
            conditions.add(eq("clientId", filter.clientId()));
        }
        if (filter.subscriptionId() != null) {
            conditions.add(eq("subscriptionId", filter.subscriptionId()));
        }
        if (filter.dispatchPoolId() != null) {
            conditions.add(eq("dispatchPoolId", filter.dispatchPoolId()));
        }
        if (filter.messageGroup() != null) {
            conditions.add(eq("messageGroup", filter.messageGroup()));
        }
        if (filter.createdAfter() != null) {
            conditions.add(gte("createdAt", filter.createdAfter()));
        }
        if (filter.createdBefore() != null) {
            conditions.add(lte("createdAt", filter.createdBefore()));
        }

        if (conditions.isEmpty()) {
            return new Document();
        }

        return and(conditions);
    }

    @Override
    public List<DispatchJob> findByMetadata(String key, String value) {
        Bson filter = elemMatch("metadata", and(eq("key", key), eq("value", value)));
        return collection().find(filter).into(new ArrayList<>());
    }

    @Override
    public List<DispatchJob> findByMetadataFilters(Map<String, String> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return List.of();
        }

        List<Bson> conditions = new ArrayList<>();
        for (Map.Entry<String, String> entry : metadataFilters.entrySet()) {
            conditions.add(elemMatch("metadata", and(eq("key", entry.getKey()), eq("value", entry.getValue()))));
        }

        return collection().find(and(conditions)).into(new ArrayList<>());
    }

    @Override
    public List<DispatchJob> findRecentPaged(int page, int size) {
        return collection().find()
            .sort(Sorts.descending("createdAt"))
            .skip(page * size)
            .limit(size)
            .into(new ArrayList<>());
    }

    @Override
    public List<DispatchJob> findPendingJobs(int limit) {
        return collection().find(eq("status", DispatchStatus.PENDING))
            .sort(Sorts.orderBy(
                Sorts.ascending("messageGroup"),
                Sorts.ascending("sequence"),
                Sorts.ascending("createdAt")
            ))
            .limit(limit)
            .into(new ArrayList<>());
    }

    @Override
    public long countByMessageGroupAndStatus(String messageGroup, DispatchStatus status) {
        return collection().countDocuments(and(eq("messageGroup", messageGroup), eq("status", status)));
    }

    @Override
    public Set<String> findGroupsWithErrors(Set<String> messageGroups) {
        if (messageGroups == null || messageGroups.isEmpty()) {
            return Set.of();
        }

        List<String> errorGroups = documentCollection()
            .distinct("messageGroup",
                and(eq("status", DispatchStatus.ERROR.name()), in("messageGroup", messageGroups)),
                String.class)
            .into(new ArrayList<>());

        return new HashSet<>(errorGroups);
    }

    @Override
    public void updateStatusBatch(List<String> ids, DispatchStatus status) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        collection().updateMany(
            in("_id", ids),
            combine(set("status", status), set("updatedAt", Instant.now()))
        );
    }

    @Override
    public List<DispatchJob> findStaleQueued(Instant threshold) {
        return collection().find(and(eq("status", DispatchStatus.QUEUED), lt("createdAt", threshold)))
            .sort(Sorts.ascending("createdAt"))
            .into(new ArrayList<>());
    }

    @Override
    public List<DispatchJob> findStaleQueued(Instant threshold, int limit) {
        return collection().find(and(eq("status", DispatchStatus.QUEUED), lt("createdAt", threshold)))
            .sort(Sorts.ascending("createdAt"))
            .limit(limit)
            .into(new ArrayList<>());
    }

    @Override
    public DispatchJob findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<DispatchJob> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public List<DispatchJob> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public void persist(DispatchJob job) {
        collection().insertOne(job);
    }

    @Override
    public void persistAll(List<DispatchJob> jobs) {
        if (jobs != null && !jobs.isEmpty()) {
            collection().insertMany(jobs);
        }
    }

    @Override
    public void update(DispatchJob job) {
        collection().replaceOne(eq("_id", job.id), job);
    }

    @Override
    public void delete(DispatchJob job) {
        collection().deleteOne(eq("_id", job.id));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
