package tech.flowcatalyst.streamprocessor.checkpoint;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Redis-backed checkpoint store for production use.
 *
 * <p>Checkpoints are persisted to Redis, allowing the change stream to resume
 * from where it left off after a restart.</p>
 *
 * <p>Only activated when stream-processor.checkpoint.redis.enabled=true</p>
 *
 * <p>Each stream has its own checkpoint key in Redis, prefixed with
 * {@code flowcatalyst:stream-processor:checkpoint:}</p>
 */
@ApplicationScoped
@IfBuildProperty(name = "stream-processor.checkpoint.redis.enabled", stringValue = "true")
public class RedisCheckpointStore implements CheckpointStore {

    private static final Logger LOG = Logger.getLogger(RedisCheckpointStore.class.getName());
    private static final String CHECKPOINT_PREFIX = "flowcatalyst:stream-processor:checkpoint:";

    @Inject
    Instance<RedissonClient> redissonClient;

    @Override
    public Optional<BsonDocument> getCheckpoint(String checkpointKey) throws CheckpointUnavailableException {
        if (!redissonClient.isResolvable()) {
            throw new CheckpointUnavailableException("Redis client not available");
        }

        String json;
        try {
            RBucket<String> bucket = redissonClient.get()
                    .getBucket(CHECKPOINT_PREFIX + checkpointKey);
            json = bucket.get();
        } catch (Exception e) {
            throw new CheckpointUnavailableException(
                    "[" + checkpointKey + "] Failed to load checkpoint from Redis: " + e.getMessage(), e);
        }

        if (json == null || json.isBlank()) {
            LOG.info("[" + checkpointKey + "] No checkpoint found in Redis - starting from beginning");
            return Optional.empty();
        }

        try {
            LOG.info("[" + checkpointKey + "] Loaded checkpoint from Redis");
            return Optional.of(BsonDocument.parse(json));
        } catch (Exception e) {
            // Invalid/corrupt checkpoint data - treat as no checkpoint
            LOG.warning("[" + checkpointKey + "] Invalid checkpoint data in Redis, ignoring: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void saveCheckpoint(String checkpointKey, BsonDocument resumeToken) {
        if (!redissonClient.isResolvable()) {
            LOG.warning("[" + checkpointKey + "] Redis not available - checkpoint not saved");
            return;
        }

        try {
            RBucket<String> bucket = redissonClient.get()
                    .getBucket(CHECKPOINT_PREFIX + checkpointKey);
            bucket.set(resumeToken.toJson());
            LOG.fine("[" + checkpointKey + "] Checkpoint saved to Redis");
        } catch (Exception e) {
            LOG.warning("[" + checkpointKey + "] Failed to save checkpoint to Redis: " + e.getMessage());
        }
    }
}
