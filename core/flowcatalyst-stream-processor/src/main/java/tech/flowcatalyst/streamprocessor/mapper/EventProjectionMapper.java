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
        projected.put("type", event.getString("type"));
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
                // Index on type - for filtering by event type
                new IndexDefinition("type", Indexes.ascending("type")),

                // Index on subject - for looking up events by aggregate
                new IndexDefinition("subject", Indexes.ascending("subject")),

                // Index on time - for time-based queries (descending for "latest first")
                new IndexDefinition("time", Indexes.descending("time")),

                // Index on messageGroup - for ordered processing queries
                new IndexDefinition("messageGroup", Indexes.ascending("messageGroup")),

                // Index on correlationId - for distributed tracing (sparse - optional field)
                new IndexDefinition("correlationId",
                        Indexes.ascending("correlationId"),
                        new IndexOptions().sparse(true)),

                // Compound index on type + time - common query pattern
                new IndexDefinition("type_time",
                        Indexes.compoundIndex(
                                Indexes.ascending("type"),
                                Indexes.descending("time"))),

                // Compound index on subject + time - for aggregate event history
                new IndexDefinition("subject_time",
                        Indexes.compoundIndex(
                                Indexes.ascending("subject"),
                                Indexes.descending("time"))),

                // Index on projectedAt - for monitoring projection lag
                new IndexDefinition("projectedAt", Indexes.descending("projectedAt")),

                // Index on clientId - for client-scoped queries
                new IndexDefinition("clientId",
                        Indexes.ascending("clientId"),
                        new IndexOptions().sparse(true))
        );
    }

    @Override
    public String getName() {
        return "EventProjectionMapper";
    }
}
