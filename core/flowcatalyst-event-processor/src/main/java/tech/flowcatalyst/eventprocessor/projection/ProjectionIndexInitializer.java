package tech.flowcatalyst.eventprocessor.projection;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.bson.Document;
import tech.flowcatalyst.eventprocessor.config.EventProcessorConfig;

import java.util.logging.Logger;

/**
 * Initializes MongoDB indexes for the events_read projection collection.
 *
 * This runs on startup to ensure indexes exist before processing begins.
 * Index creation is idempotent - if the index already exists, nothing happens.
 *
 * Indexes are optimized for common query patterns:
 * - By type (event type filtering)
 * - By subject (aggregate lookup)
 * - By time (time-based queries)
 * - By messageGroup (ordered processing)
 * - By correlationId (distributed tracing)
 * - Compound type+time (common filter pattern)
 */
@ApplicationScoped
public class ProjectionIndexInitializer {

    private static final Logger LOG = Logger.getLogger(ProjectionIndexInitializer.class.getName());

    @Inject
    EventProcessorConfig config;

    @Inject
    MongoClient mongoClient;

    void onStartup(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.fine("Event processor disabled - skipping index initialization");
            return;
        }

        LOG.info("Initializing indexes for projection collection: " + config.projectionCollection());

        try {
            createIndexes();
            LOG.info("Index initialization completed");
        } catch (Exception e) {
            LOG.severe("Failed to create indexes: " + e.getMessage());
            // Don't fail startup - indexes can be created manually if needed
        }
    }

    private void createIndexes() {
        MongoCollection<Document> collection = mongoClient
                .getDatabase(config.database())
                .getCollection(config.projectionCollection());

        // Note: _id index is created automatically by MongoDB
        // Our _id is the eventId, so we get idempotency for free

        // Index on type - for filtering by event type
        createIndex(collection, "type", Indexes.ascending("type"));

        // Index on subject - for looking up events by aggregate
        createIndex(collection, "subject", Indexes.ascending("subject"));

        // Index on time - for time-based queries (descending for "latest first")
        createIndex(collection, "time", Indexes.descending("time"));

        // Index on messageGroup - for ordered processing queries
        createIndex(collection, "messageGroup", Indexes.ascending("messageGroup"));

        // Index on correlationId - for distributed tracing
        // Sparse because correlationId is optional
        createIndex(collection, "correlationId",
                Indexes.ascending("correlationId"),
                new IndexOptions().sparse(true));

        // Compound index on type + time - common query pattern
        createIndex(collection, "type_time",
                Indexes.compoundIndex(
                        Indexes.ascending("type"),
                        Indexes.descending("time")
                ));

        // Compound index on subject + time - for aggregate event history
        createIndex(collection, "subject_time",
                Indexes.compoundIndex(
                        Indexes.ascending("subject"),
                        Indexes.descending("time")
                ));

        // Index on projectedAt - for monitoring projection lag
        createIndex(collection, "projectedAt", Indexes.descending("projectedAt"));
    }

    private void createIndex(MongoCollection<Document> collection, String name, org.bson.conversions.Bson keys) {
        createIndex(collection, name, keys, new IndexOptions());
    }

    private void createIndex(MongoCollection<Document> collection, String name,
                            org.bson.conversions.Bson keys, IndexOptions options) {
        try {
            collection.createIndex(keys, options.name(name));
            LOG.fine("Created index: " + name);
        } catch (Exception e) {
            // Index might already exist with different options
            LOG.warning("Could not create index " + name + ": " + e.getMessage());
        }
    }
}
