package tech.flowcatalyst.streamprocessor.mapper;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.bson.Document;

import java.time.Instant;
import java.util.List;

/**
 * Projection mapper for dispatch jobs.
 *
 * <p>Transforms documents from the {@code dispatch_jobs} collection into the
 * {@code dispatch_jobs_read} projection collection. This is a light projection
 * that excludes large fields (payload, headers, attempts) for efficient listing
 * and dashboard queries.</p>
 *
 * <p>This mapper is referenced by name "dispatch-jobs" in configuration:</p>
 * <pre>
 * stream-processor.streams.dispatch-jobs.mapper=dispatch-jobs
 * stream-processor.streams.dispatch-jobs.watch-operations=insert,update
 * </pre>
 *
 * <p>Unlike events (INSERT only), dispatch jobs use INSERT + UPDATE watching
 * so the projection stays in sync as job status changes.</p>
 */
@ApplicationScoped
@Named("dispatch-jobs")
public class DispatchJobProjectionMapper implements ProjectionMapper {

    @Override
    public Document toProjection(Document source) {
        Document projected = new Document();

        // ID handling
        Object rawId = source.get("_id");
        String id = rawId != null ? rawId.toString() : null;
        projected.put("_id", id);
        projected.put("dispatchJobId", id);

        // Core identifiers
        projected.put("externalId", source.getString("externalId"));
        projected.put("source", source.getString("source"));
        projected.put("kind", source.getString("kind"));
        projected.put("code", source.getString("code"));
        projected.put("subject", source.getString("subject"));
        projected.put("eventId", source.getString("eventId"));
        projected.put("correlationId", source.getString("correlationId"));

        // Target (URL only, no headers for light projection)
        projected.put("targetUrl", source.getString("targetUrl"));
        projected.put("protocol", source.getString("protocol"));

        // Context
        projected.put("clientId", source.getString("clientId"));
        projected.put("subscriptionId", source.getString("subscriptionId"));
        projected.put("serviceAccountId", source.getString("serviceAccountId"));
        projected.put("dispatchPoolId", source.getString("dispatchPoolId"));
        projected.put("messageGroup", source.getString("messageGroup"));
        projected.put("mode", source.getString("mode"));
        projected.put("sequence", source.getInteger("sequence"));

        // Status tracking
        projected.put("status", source.getString("status"));
        projected.put("attemptCount", source.getInteger("attemptCount"));
        projected.put("maxRetries", source.getInteger("maxRetries"));
        projected.put("lastError", source.getString("lastError"));

        // Timing
        projected.put("timeoutSeconds", source.getInteger("timeoutSeconds"));
        projected.put("retryStrategy", source.getString("retryStrategy"));

        // Timestamps
        projected.put("createdAt", source.get("createdAt"));
        projected.put("updatedAt", source.get("updatedAt"));
        projected.put("scheduledFor", source.get("scheduledFor"));
        projected.put("expiresAt", source.get("expiresAt"));
        projected.put("completedAt", source.get("completedAt"));
        projected.put("lastAttemptAt", source.get("lastAttemptAt"));
        projected.put("durationMillis", source.get("durationMillis"));

        // Idempotency
        projected.put("idempotencyKey", source.getString("idempotencyKey"));

        // Computed fields for read optimization
        String status = source.getString("status");
        projected.put("isCompleted",
                "COMPLETED".equals(status) || "ERROR".equals(status) || "CANCELLED".equals(status));
        projected.put("isTerminal",
                "COMPLETED".equals(status) || "ERROR".equals(status) || "CANCELLED".equals(status));
        projected.put("projectedAt", Instant.now());

        // Excluded from light projection:
        // - payload (large)
        // - headers (Map, variable size)
        // - attempts (List, grows over time)
        // - metadata (List, variable)
        // - payloadContentType
        // - dataOnly
        // - schemaId

        return projected;
    }

    @Override
    public List<IndexDefinition> getIndexDefinitions() {
        return List.of(
                // Primary status-based queries
                new IndexDefinition("status", Indexes.ascending("status")),

                // Client and subscription scoping
                new IndexDefinition("clientId",
                        Indexes.ascending("clientId"),
                        new IndexOptions().sparse(true)),
                new IndexDefinition("subscriptionId",
                        Indexes.ascending("subscriptionId"),
                        new IndexOptions().sparse(true)),

                // Event correlation
                new IndexDefinition("eventId",
                        Indexes.ascending("eventId"),
                        new IndexOptions().sparse(true)),

                // Distributed tracing
                new IndexDefinition("correlationId",
                        Indexes.ascending("correlationId"),
                        new IndexOptions().sparse(true)),

                // Time-based queries
                new IndexDefinition("createdAt", Indexes.descending("createdAt")),
                new IndexDefinition("scheduledFor",
                        Indexes.ascending("scheduledFor"),
                        new IndexOptions().sparse(true)),

                // Dispatch pool and message group
                new IndexDefinition("dispatchPoolId",
                        Indexes.ascending("dispatchPoolId"),
                        new IndexOptions().sparse(true)),
                new IndexDefinition("messageGroup",
                        Indexes.ascending("messageGroup"),
                        new IndexOptions().sparse(true)),

                // Compound indexes for common query patterns
                new IndexDefinition("status_scheduledFor",
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.ascending("scheduledFor"))),

                new IndexDefinition("clientId_status_createdAt",
                        Indexes.compoundIndex(
                                Indexes.ascending("clientId"),
                                Indexes.ascending("status"),
                                Indexes.descending("createdAt"))),

                new IndexDefinition("subscriptionId_createdAt",
                        Indexes.compoundIndex(
                                Indexes.ascending("subscriptionId"),
                                Indexes.descending("createdAt"))),

                new IndexDefinition("code_status",
                        Indexes.compoundIndex(
                                Indexes.ascending("code"),
                                Indexes.ascending("status"))),

                // Projection tracking
                new IndexDefinition("projectedAt", Indexes.descending("projectedAt")),

                // Idempotency key (unique, sparse)
                new IndexDefinition("idempotencyKey",
                        Indexes.ascending("idempotencyKey"),
                        new IndexOptions().unique(true).sparse(true))
        );
    }

    @Override
    public String getName() {
        return "DispatchJobProjectionMapper";
    }
}
