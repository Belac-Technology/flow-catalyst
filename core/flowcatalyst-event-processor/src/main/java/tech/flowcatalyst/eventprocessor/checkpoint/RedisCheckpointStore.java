package tech.flowcatalyst.eventprocessor.checkpoint;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import tech.flowcatalyst.eventprocessor.config.EventProcessorConfig;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Redis-backed checkpoint store for production use.
 *
 * Checkpoints are persisted to Redis, allowing the change stream to resume
 * from where it left off after a restart.
 *
 * Only activated when event-processor.checkpoint.redis.enabled=true
 */
@ApplicationScoped
@IfBuildProperty(name = "event-processor.checkpoint.redis.enabled", stringValue = "true")
public class RedisCheckpointStore implements CheckpointStore {

    private static final Logger LOG = Logger.getLogger(RedisCheckpointStore.class.getName());
    private static final String CHECKPOINT_PREFIX = "flowcatalyst:event-processor:checkpoint:";

    @Inject
    EventProcessorConfig config;

    @Inject
    Instance<RedissonClient> redissonClient;

    @Override
    public Optional<BsonDocument> getCheckpoint() throws CheckpointUnavailableException {
        if (!redissonClient.isResolvable()) {
            throw new CheckpointUnavailableException("Redis client not available");
        }

        String json;
        try {
            RBucket<String> bucket = redissonClient.get()
                    .getBucket(CHECKPOINT_PREFIX + config.checkpointKey());
            json = bucket.get();
        } catch (Exception e) {
            throw new CheckpointUnavailableException("Failed to load checkpoint from Redis: " + e.getMessage(), e);
        }

        if (json == null || json.isBlank()) {
            LOG.info("No checkpoint found in Redis - starting change stream from beginning");
            return Optional.empty();
        }

        try {
            LOG.info("Loaded checkpoint from Redis");
            return Optional.of(BsonDocument.parse(json));
        } catch (Exception e) {
            // Invalid/corrupt checkpoint data - treat as no checkpoint
            LOG.warning("Invalid checkpoint data in Redis, ignoring: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void saveCheckpoint(BsonDocument resumeToken) {
        if (!redissonClient.isResolvable()) {
            LOG.warning("Redis not available - checkpoint not saved");
            return;
        }

        try {
            RBucket<String> bucket = redissonClient.get()
                    .getBucket(CHECKPOINT_PREFIX + config.checkpointKey());
            bucket.set(resumeToken.toJson());
            LOG.fine("Checkpoint saved to Redis");
        } catch (Exception e) {
            LOG.warning("Failed to save checkpoint to Redis: " + e.getMessage());
        }
    }
}
