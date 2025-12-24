package tech.flowcatalyst.streamprocessor.mapper;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.bson.Document;

import java.time.Instant;
import java.util.List;

/**
 * Projection mapper for events.
 *
 * <p>Transforms documents from the {@code events} collection into the
 * {@code events_read} projection collection. The projection includes
 * all CloudEvents fields plus tracing metadata.</p>
 *
 * <p>This mapper is referenced by name "events" in configuration:</p>
 * <pre>
 * stream-processor.streams.events.mapper=events
 * </pre>
 */
@ApplicationScoped
@Named("events")
public class EventProjectionMapper implements ProjectionMapper {

    @Override
    public Document toProjection(Document event) {
        Document projected = new Document();

        // Use eventId as _id for automatic unique index and idempotency
        Object rawId = event.get("_id");
        String eventId = rawId != null ? rawId.toString() : null;
        projected.put("_id", eventId);
        projected.put("eventId", eventId);

        // CloudEvents core fields
        projected.put("specVersion", event.getString("specVersion"));
        String type = event.getString("type");
        projected.put("type", type);

        // Parse type into denormalized filter fields: {app}:{subdomain}:{aggregate}:{event}
        String[] typeSegments = type != null ? type.split(":", 4) : new String[0];
        projected.put("application", typeSegments.length > 0 ? typeSegments[0] : null);
        projected.put("subdomain", typeSegments.length > 1 ? typeSegments[1] : null);
        projected.put("aggregate", typeSegments.length > 2 ? typeSegments[2] : null);

        projected.put("source", event.getString("source"));
        projected.put("subject", event.getString("subject"));
        projected.put("time", event.get("time"));
        projected.put("data", event.getString("data"));

        // Tracing and correlation
        projected.put("messageGroup", event.getString("messageGroup"));
        projected.put("correlationId", event.getString("correlationId"));
        projected.put("causationId", event.getString("causationId"));
        projected.put("deduplicationId", event.getString("deduplicationId"));

        // Context data for filtering
        projected.put("contextData", event.get("contextData"));

        // Client context
        projected.put("clientId", event.getString("clientId"));

        // Projection metadata
        projected.put("projectedAt", Instant.now());

        return projected;
    }

    @Override
    public List<IndexDefinition> getIndexDefinitions() {
        return List.of(
                // =============================================================
                // Global cascading filter indexes (no clientId filter)
                // Supports: app, app+subdomain, app+subdomain+aggregate, full type
                // =============================================================

                // Global cascading filter - covers all non-client-scoped filter combos
                new IndexDefinition("app_subdomain_aggregate_type_time",
                        Indexes.compoundIndex(
                                Indexes.ascending("application"),
                                Indexes.ascending("subdomain"),
                                Indexes.ascending("aggregate"),
                                Indexes.ascending("type"),
                                Indexes.descending("time"))),

                // Global subject + time - for aggregate history across all
                new IndexDefinition("subject_time",
                        Indexes.compoundIndex(
                                Indexes.ascending("subject"),
                                Indexes.descending("time"))),

                // =============================================================
                // Client-scoped cascading filter indexes
                // Supports: client, client+app, client+app+subdomain, etc.
                // =============================================================

                // Client-scoped cascading filter - covers all client-scoped filter combos
                new IndexDefinition("clientId_app_subdomain_aggregate_type_time",
                        Indexes.compoundIndex(
                                Indexes.ascending("clientId"),
                                Indexes.ascending("application"),
                                Indexes.ascending("subdomain"),
                                Indexes.ascending("aggregate"),
                                Indexes.ascending("type"),
                                Indexes.descending("time"))),

                // Client + subject + time - aggregate history within client
                new IndexDefinition("clientId_subject_time",
                        Indexes.compoundIndex(
                                Indexes.ascending("clientId"),
                                Indexes.ascending("subject"),
                                Indexes.descending("time"))),

                // =============================================================
                // Tracing and monitoring indexes
                // =============================================================

                // Correlation ID - for distributed tracing (sparse - truly optional)
                new IndexDefinition("correlationId",
                        Indexes.ascending("correlationId"),
                        new IndexOptions().sparse(true)),

                // Client + message group - for ordered processing within client context
                new IndexDefinition("clientId_messageGroup",
                        Indexes.compoundIndex(
                                Indexes.ascending("clientId"),
                                Indexes.ascending("messageGroup")),
                        new IndexOptions().sparse(true)),

                // Context data key/value lookup - multikey index for querying by contextData entries
                new IndexDefinition("contextData_key_value",
                        Indexes.compoundIndex(
                                Indexes.ascending("contextData.key"),
                                Indexes.ascending("contextData.value")),
                        new IndexOptions().sparse(true)),

                // Projection lag monitoring
                new IndexDefinition("projectedAt", Indexes.descending("projectedAt"))
        );
    }

    @Override
    public String getName() {
        return "EventProjectionMapper";
    }
}
