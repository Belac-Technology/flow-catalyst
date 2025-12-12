package tech.flowcatalyst.eventprocessor.checkpoint;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.BsonDocument;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * In-memory checkpoint store for development and testing.
 *
 * Checkpoints are lost on restart, so the change stream will start
 * from the beginning each time. This is fine for dev/test but not
 * suitable for production.
 *
 * This is the default implementation - Redis implementation takes
 * priority when available.
 */
@ApplicationScoped
@DefaultBean
public class InMemoryCheckpointStore implements CheckpointStore {

    private static final Logger LOG = Logger.getLogger(InMemoryCheckpointStore.class.getName());

    private volatile BsonDocument checkpoint = null;

    @Override
    public Optional<BsonDocument> getCheckpoint() {
        if (checkpoint == null) {
            LOG.info("No checkpoint in memory - starting change stream from beginning");
            return Optional.empty();
        }
        LOG.info("Loaded checkpoint from memory");
        return Optional.of(checkpoint);
    }

    @Override
    public void saveCheckpoint(BsonDocument resumeToken) {
        this.checkpoint = resumeToken;
        LOG.fine("Checkpoint saved to memory");
    }
}
