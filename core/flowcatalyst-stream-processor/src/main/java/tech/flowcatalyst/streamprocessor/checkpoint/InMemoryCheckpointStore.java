package tech.flowcatalyst.streamprocessor.checkpoint;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.BsonDocument;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory checkpoint store for development and testing.
 *
 * <p>Checkpoints are lost on restart, so the change stream will start
 * from the beginning each time. This is fine for dev/test but not
 * suitable for production.</p>
 *
 * <p>Only activated when stream-processor.checkpoint.mongo.enabled=false</p>
 *
 * <p>Supports multiple streams via a concurrent map keyed by checkpoint key.</p>
 */
@ApplicationScoped
@IfBuildProperty(name = "stream-processor.checkpoint.mongo.enabled", stringValue = "false")
public class InMemoryCheckpointStore implements CheckpointStore {

    private static final Logger LOG = Logger.getLogger(InMemoryCheckpointStore.class.getName());

    private final Map<String, BsonDocument> checkpoints = new ConcurrentHashMap<>();

    @Override
    public Optional<BsonDocument> getCheckpoint(String checkpointKey) {
        BsonDocument checkpoint = checkpoints.get(checkpointKey);
        if (checkpoint == null) {
            LOG.info("[" + checkpointKey + "] No checkpoint in memory - starting from beginning");
            return Optional.empty();
        }
        LOG.info("[" + checkpointKey + "] Loaded checkpoint from memory");
        return Optional.of(checkpoint);
    }

    @Override
    public void saveCheckpoint(String checkpointKey, BsonDocument resumeToken) {
        checkpoints.put(checkpointKey, resumeToken);
        LOG.fine("[" + checkpointKey + "] Checkpoint saved to memory");
    }

    /**
     * Clear all checkpoints (for testing).
     */
    public void clear() {
        checkpoints.clear();
    }
}
