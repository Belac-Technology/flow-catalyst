package tech.flowcatalyst.streamprocessor.checkpoint;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * MongoDB-backed checkpoint store.
 *
 * <p>Checkpoints are persisted to MongoDB, allowing the change stream to resume
 * from where it left off after a restart. This is the default checkpoint store
 * and is ideal for single-instance deployments where MongoDB is already available.</p>
 *
 * <p>Only activated when stream-processor.checkpoint.mongo.enabled=true (default)</p>
 *
 * <p>Checkpoints are stored in the {@code stream_checkpoints} collection with
 * the checkpoint key as the document _id.</p>
 */
@ApplicationScoped
@DefaultBean
@IfBuildProperty(name = "stream-processor.checkpoint.mongo.enabled", stringValue = "true", enableIfMissing = true)
public class MongoCheckpointStore implements CheckpointStore {

    private static final Logger LOG = Logger.getLogger(MongoCheckpointStore.class.getName());
    private static final String COLLECTION = "stream_checkpoints";

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "stream-processor.database", defaultValue = "flowcatalyst")
    String database;

    private MongoCollection<Document> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public Optional<BsonDocument> getCheckpoint(String checkpointKey) throws CheckpointUnavailableException {
        try {
            Document doc = getCollection()
                    .find(Filters.eq("_id", checkpointKey))
                    .first();

            if (doc == null) {
                LOG.info("[" + checkpointKey + "] No checkpoint found in MongoDB - starting from beginning");
                return Optional.empty();
            }

            String tokenJson = doc.getString("token");
            if (tokenJson == null || tokenJson.isBlank()) {
                LOG.info("[" + checkpointKey + "] Empty checkpoint in MongoDB - starting from beginning");
                return Optional.empty();
            }

            try {
                LOG.info("[" + checkpointKey + "] Loaded checkpoint from MongoDB");
                return Optional.of(BsonDocument.parse(tokenJson));
            } catch (Exception e) {
                // Invalid/corrupt checkpoint data - treat as no checkpoint
                LOG.warning("[" + checkpointKey + "] Invalid checkpoint data in MongoDB, ignoring: " + e.getMessage());
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new CheckpointUnavailableException(
                    "[" + checkpointKey + "] Failed to load checkpoint from MongoDB: " + e.getMessage(), e);
        }
    }

    @Override
    public void saveCheckpoint(String checkpointKey, BsonDocument resumeToken) {
        try {
            Document doc = new Document("_id", checkpointKey)
                    .append("token", resumeToken.toJson())
                    .append("updatedAt", Instant.now().toString());

            getCollection().replaceOne(
                    Filters.eq("_id", checkpointKey),
                    doc,
                    new ReplaceOptions().upsert(true)
            );
            LOG.fine("[" + checkpointKey + "] Checkpoint saved to MongoDB");
        } catch (Exception e) {
            LOG.warning("[" + checkpointKey + "] Failed to save checkpoint to MongoDB: " + e.getMessage());
        }
    }

    /**
     * Clear a checkpoint (for recovery from stale resume token).
     *
     * @param checkpointKey the checkpoint key to clear
     */
    public void clearCheckpoint(String checkpointKey) {
        try {
            getCollection().deleteOne(Filters.eq("_id", checkpointKey));
            LOG.info("[" + checkpointKey + "] Checkpoint cleared from MongoDB");
        } catch (Exception e) {
            LOG.warning("[" + checkpointKey + "] Failed to clear checkpoint from MongoDB: " + e.getMessage());
        }
    }
}
