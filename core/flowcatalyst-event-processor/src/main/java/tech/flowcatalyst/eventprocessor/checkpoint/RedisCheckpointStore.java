package tech.flowcatalyst.eventprocessor.checkpoint;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import tech.flowcatalyst.eventprocessor.config.EventProcessorConfig;

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
@LookupIfProperty(name = "event-processor.checkpoint.redis.enabled", stringValue = "true")
public class RedisCheckpointStore implements CheckpointStore {

    private static final Logger LOG = Logger.getLogger(RedisCheckpointStore.class.getName());
    private static final String CHECKPOINT_PREFIX = "flowcatalyst:event-processor:checkpoint:";

    @Inject
    EventProcessorConfig config;

    @Inject
    Instance<RedissonClient> redissonClient;

    @Override
    public BsonDocument getCheckpoint() {
        if (!redissonClient.isResolvable()) {
            LOG.warning("Redis not available - starting change stream from beginning");
            return null;
        }

        try {
            RBucket<String> bucket = redissonClient.get()
                    .getBucket(CHECKPOINT_PREFIX + config.checkpointKey());
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                LOG.info("No checkpoint found in Redis - starting change stream from beginning");
                return null;
            }
            LOG.info("Loaded checkpoint from Redis");
            return BsonDocument.parse(json);
        } catch (Exception e) {
            LOG.warning("Failed to load checkpoint from Redis: " + e.getMessage());
            return null;
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
