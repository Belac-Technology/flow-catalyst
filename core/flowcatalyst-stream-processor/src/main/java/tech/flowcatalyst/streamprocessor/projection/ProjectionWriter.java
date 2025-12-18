package tech.flowcatalyst.streamprocessor.projection;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import tech.flowcatalyst.streamprocessor.config.StreamConfig;
import tech.flowcatalyst.streamprocessor.config.StreamProcessorConfig;
import tech.flowcatalyst.streamprocessor.mapper.ProjectionMapper;

import java.util.List;
import java.util.logging.Logger;

import static com.mongodb.client.model.Filters.eq;

/**
 * Writes batches of documents to the projection collection.
 *
 * <p>Uses a {@link ProjectionMapper} to transform source documents into
 * the projection format before writing.</p>
 *
 * <p>Supports two modes based on operation type:</p>
 * <ul>
 *   <li><b>INSERT</b>: Batch insert with ordered=false for idempotency</li>
 *   <li><b>UPDATE/REPLACE</b>: Individual upserts to update existing projections</li>
 * </ul>
 *
 * <p>Note: This class is NOT a CDI bean. Each stream gets its own instance
 * created by the StreamProcessorStarter.</p>
 */
public class ProjectionWriter {

    private static final Logger LOG = Logger.getLogger(ProjectionWriter.class.getName());
    private static final int DUPLICATE_KEY_ERROR = 11000;

    private final String streamName;
    private final MongoClient mongoClient;
    private final StreamProcessorConfig rootConfig;
    private final StreamConfig streamConfig;
    private final ProjectionMapper mapper;

    /**
     * Create a new projection writer for a stream.
     *
     * @param streamName   name of the stream (for logging)
     * @param mongoClient  MongoDB client
     * @param rootConfig   root stream processor configuration
     * @param streamConfig this stream's configuration
     * @param mapper       projection mapper for this stream
     */
    public ProjectionWriter(String streamName, MongoClient mongoClient,
                            StreamProcessorConfig rootConfig, StreamConfig streamConfig,
                            ProjectionMapper mapper) {
        this.streamName = streamName;
        this.mongoClient = mongoClient;
        this.rootConfig = rootConfig;
        this.streamConfig = streamConfig;
        this.mapper = mapper;
    }

    /**
     * Write a batch of documents to the projection collection.
     *
     * @param documents     the raw documents from the change stream
     * @param operationType the change stream operation type (insert, update, replace)
     * @throws BatchWriteException if the batch fails with non-duplicate errors
     */
    public void writeBatch(List<Document> documents, String operationType) throws BatchWriteException {
        if (documents.isEmpty()) {
            return;
        }

        MongoCollection<Document> projection = mongoClient
                .getDatabase(rootConfig.database())
                .getCollection(streamConfig.projectionCollection());

        // Transform documents using the mapper
        List<Document> projectedDocs = documents.stream()
                .map(mapper::toProjection)
                .toList();

        if ("insert".equals(operationType)) {
            writeInserts(projection, projectedDocs);
        } else {
            // For update/replace, use upsert for each document
            writeUpserts(projection, projectedDocs);
        }
    }

    /**
     * Write documents as batch inserts with idempotency handling.
     */
    private void writeInserts(MongoCollection<Document> projection, List<Document> projectedDocs)
            throws BatchWriteException {
        try {
            // ordered=false means continue inserting even if some fail
            projection.insertMany(projectedDocs, new InsertManyOptions().ordered(false));
            LOG.fine("[" + streamName + "] Batch of " + projectedDocs.size() + " documents inserted");
        } catch (MongoBulkWriteException e) {
            // Filter to only non-duplicate errors
            List<BulkWriteError> realErrors = e.getWriteErrors().stream()
                    .filter(err -> err.getCode() != DUPLICATE_KEY_ERROR)
                    .toList();

            if (!realErrors.isEmpty()) {
                throw new BatchWriteException(
                        "[" + streamName + "] Batch write failed with " + realErrors.size() + " non-duplicate errors",
                        realErrors,
                        e
                );
            }

            // All errors were duplicates - that's fine (idempotent replay)
            int duplicates = e.getWriteErrors().size();
            int inserted = projectedDocs.size() - duplicates;
            LOG.fine("[" + streamName + "] Batch: " + inserted + " inserted, " + duplicates + " duplicates skipped");
        }
    }

    /**
     * Write documents as individual upserts (for update/replace operations).
     */
    private void writeUpserts(MongoCollection<Document> projection, List<Document> projectedDocs)
            throws BatchWriteException {
        int updated = 0;
        int inserted = 0;

        for (Document doc : projectedDocs) {
            Object id = doc.get("_id");
            if (id == null) {
                LOG.warning("[" + streamName + "] Skipping document without _id");
                continue;
            }

            try {
                Bson filter = eq("_id", id);
                var result = projection.replaceOne(filter, doc, new ReplaceOptions().upsert(true));

                if (result.getModifiedCount() > 0) {
                    updated++;
                } else if (result.getUpsertedId() != null) {
                    inserted++;
                }
            } catch (Exception e) {
                throw new BatchWriteException(
                        "[" + streamName + "] Failed to upsert document " + id + ": " + e.getMessage(),
                        List.of(),
                        e
                );
            }
        }

        LOG.fine("[" + streamName + "] Batch upsert: " + updated + " updated, " + inserted + " inserted");
    }

    /**
     * Get the stream name.
     */
    public String getStreamName() {
        return streamName;
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
