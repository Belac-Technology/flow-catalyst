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
        String code = source.getString("code");
        projected.put("code", code);
        projected.put("subject", source.getString("subject"));
        projected.put("eventId", source.getString("eventId"));
        projected.put("correlationId", source.getString("correlationId"));

        // Parse code into denormalized filter fields: {app}:{subdomain}:{aggregate}:{event}
        String[] codeSegments = code != null ? code.split(":", 4) : new String[0];
        projected.put("application", codeSegments.length > 0 ? codeSegments[0] : null);
        projected.put("subdomain", codeSegments.length > 1 ? codeSegments[1] : null);
        projected.put("aggregate", codeSegments.length > 2 ? codeSegments[2] : null);

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
                // =============================================================
                // Global indexes (work for platform jobs where clientId=null)
                // =============================================================

                // Global status + createdAt - for status filtering across all
                new IndexDefinition("status_createdAt",
                        Indexes.compoundIndex(
                                Indexes.ascending("status"),
                                Indexes.descending("createdAt"))),

                // Global code + status - for filtering by job type
                new IndexDefinition("code_status",
                        Indexes.compoundIndex(
                                Indexes.ascending("code"),
                                Indexes.ascending("status"))),

                // Global cascading filter - covers all non-client-scoped filter combos
                new IndexDefinition("app_subdomain_aggregate_code_createdAt",
                        Indexes.compoundIndex(
                                Indexes.ascending("application"),
                                Indexes.ascending("subdomain"),
                                Indexes.ascending("aggregate"),
                                Indexes.ascending("code"),
                                Indexes.descending("createdAt"))),

                // =============================================================
                // Client-scoped indexes (clientId NOT sparse - allows null queries)
                // =============================================================

                // Client + status + createdAt - most common UI query pattern
                new IndexDefinition("clientId_status_createdAt",
                        Indexes.compoundIndex(
                                Indexes.ascending("clientId"),
                                Indexes.ascending("status"),
                                Indexes.descending("createdAt"))),

                // Client + messageGroup + status - FIFO ordering within client
                new IndexDefinition("clientId_messageGroup_status",
                        Indexes.compoundIndex(
                                Indexes.ascending("clientId"),
                                Indexes.ascending("messageGroup"),
                                Indexes.ascending("status"))),

                // Client-scoped cascading filter
                new IndexDefinition("clientId_app_subdomain_aggregate_code_createdAt",
                        Indexes.compoundIndex(
                                Indexes.ascending("clientId"),
                                Indexes.ascending("application"),
                                Indexes.ascending("subdomain"),
                                Indexes.ascending("aggregate"),
                                Indexes.ascending("code"),
                                Indexes.descending("createdAt"))),

                // =============================================================
                // Relationship indexes
                // =============================================================

                // Subscription drill-down (subscriptionId implies client context)
                new IndexDefinition("subscriptionId_createdAt",
                        Indexes.compoundIndex(
                                Indexes.ascending("subscriptionId"),
                                Indexes.descending("createdAt")),
                        new IndexOptions().sparse(true)),

                // Dispatch pool queries
                new IndexDefinition("dispatchPoolId_status",
                        Indexes.compoundIndex(
                                Indexes.ascending("dispatchPoolId"),
                                Indexes.ascending("status")),
                        new IndexOptions().sparse(true)),

                // Event correlation - find jobs for an event
                new IndexDefinition("eventId",
                        Indexes.ascending("eventId"),
                        new IndexOptions().sparse(true)),

                // =============================================================
                // Tracing and monitoring indexes
                // =============================================================

                // Correlation ID - for distributed tracing
                new IndexDefinition("correlationId",
                        Indexes.ascending("correlationId"),
                        new IndexOptions().sparse(true)),

                // Projection lag monitoring
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
