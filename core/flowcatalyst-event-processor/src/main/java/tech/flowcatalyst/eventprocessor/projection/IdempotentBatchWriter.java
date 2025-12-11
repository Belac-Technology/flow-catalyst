package tech.flowcatalyst.eventprocessor.projection;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertManyOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import tech.flowcatalyst.eventprocessor.config.EventProcessorConfig;

import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Writes batches of events to the projection collection idempotently.
 *
 * Uses MongoDB's insertMany with ordered=false, which means:
 * - Each document insert is attempted independently
 * - Duplicate key errors (code 11000) are expected and ignored
 * - Other errors cause the batch to fail
 *
 * The projection collection uses eventId as _id, ensuring idempotency.
 */
@ApplicationScoped
public class IdempotentBatchWriter {

    private static final Logger LOG = Logger.getLogger(IdempotentBatchWriter.class.getName());
    private static final int DUPLICATE_KEY_ERROR = 11000;

    @Inject
    EventProcessorConfig config;

    @Inject
    MongoClient mongoClient;

    /**
     * Write a batch of event documents to the projection collection.
     *
     * @param events the raw event documents from the change stream
     * @throws BatchWriteException if the batch fails with non-duplicate errors
     */
    public void writeBatch(List<Document> events) throws BatchWriteException {
        if (events.isEmpty()) {
            return;
        }

        MongoCollection<Document> projection = mongoClient
                .getDatabase(config.database())
                .getCollection(config.projectionCollection());

        // Transform events to projection format
        List<Document> projectedDocs = events.stream()
                .map(this::toProjectedDocument)
                .toList();

        try {
            // ordered=false means continue inserting even if some fail
            projection.insertMany(projectedDocs, new InsertManyOptions().ordered(false));
            LOG.fine("Batch of " + events.size() + " events written successfully");
        } catch (MongoBulkWriteException e) {
            // Filter to only non-duplicate errors
            List<BulkWriteError> realErrors = e.getWriteErrors().stream()
                    .filter(err -> err.getCode() != DUPLICATE_KEY_ERROR)
                    .toList();

            if (!realErrors.isEmpty()) {
                throw new BatchWriteException(
                        "Batch write failed with " + realErrors.size() + " non-duplicate errors",
                        realErrors,
                        e
                );
            }

            // All errors were duplicates - that's fine (idempotent replay)
            int duplicates = e.getWriteErrors().size();
            int inserted = events.size() - duplicates;
            LOG.fine("Batch: " + inserted + " inserted, " + duplicates + " duplicates skipped");
        }
    }

    /**
     * Transform a raw event document into the projection format.
     *
     * The projection uses eventId as _id for idempotency via unique index.
     */
    private Document toProjectedDocument(Document event) {
        Document projected = new Document();

        // Use eventId as _id for automatic unique index and idempotency
        // The _id might be a String or an ObjectId depending on the source
        Object rawId = event.get("_id");
        String eventId = rawId != null ? rawId.toString() : null;
        projected.put("_id", eventId);
        projected.put("eventId", eventId);

        // Copy event fields
        projected.put("specVersion", event.getString("specVersion"));
        projected.put("type", event.getString("type"));
        projected.put("source", event.getString("source"));
        projected.put("subject", event.getString("subject"));
        projected.put("time", event.get("time"));
        projected.put("data", event.getString("data"));
        projected.put("messageGroup", event.getString("messageGroup"));
        projected.put("correlationId", event.getString("correlationId"));
        projected.put("causationId", event.getString("causationId"));
        projected.put("deduplicationId", event.getString("deduplicationId"));
        projected.put("contextData", event.get("contextData"));

        // Add projection metadata
        projected.put("projectedAt", Instant.now());

        return projected;
    }

    /**
     * Exception thrown when a batch write fails with non-duplicate errors.
     */
    public static class BatchWriteException extends Exception {
        private final List<BulkWriteError> errors;

        public BatchWriteException(String message, List<BulkWriteError> errors, Throwable cause) {
            super(message, cause);
            this.errors = errors;
        }

        public List<BulkWriteError> getErrors() {
            return errors;
        }
    }
}
