package tech.flowcatalyst.streamprocessor.checkpoint;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.BsonDocument;

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
 *
 * <p>Uses Quarkus Redis client for native image compatibility.</p>
 */
@ApplicationScoped
@IfBuildProperty(name = "stream-processor.checkpoint.redis.enabled", stringValue = "true")
public class RedisCheckpointStore implements CheckpointStore {

    private static final Logger LOG = Logger.getLogger(RedisCheckpointStore.class.getName());
    private static final String CHECKPOINT_PREFIX = "flowcatalyst:stream-processor:checkpoint:";

    @Inject
    Instance<RedisDataSource> redisDataSourceInstance;

    private ValueCommands<String, String> valueCommands;
    private KeyCommands<String> keyCommands;
    private boolean initialized = false;

    @PostConstruct
    void init() {
        if (redisDataSourceInstance.isResolvable()) {
            RedisDataSource redis = redisDataSourceInstance.get();
            this.valueCommands = redis.value(String.class, String.class);
            this.keyCommands = redis.key(String.class);
            this.initialized = true;
            LOG.info("RedisCheckpointStore initialized with Quarkus Redis client");
        } else {
            LOG.warning("Redis client not available for checkpoint store");
        }
    }

    @Override
    public Optional<BsonDocument> getCheckpoint(String checkpointKey) throws CheckpointUnavailableException {
        if (!initialized) {
            throw new CheckpointUnavailableException("Redis client not available");
        }

        String json;
        try {
            json = valueCommands.get(CHECKPOINT_PREFIX + checkpointKey);
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
        if (!initialized) {
            LOG.warning("[" + checkpointKey + "] Redis not available - checkpoint not saved");
            return;
        }

        try {
            valueCommands.set(CHECKPOINT_PREFIX + checkpointKey, resumeToken.toJson());
            LOG.fine("[" + checkpointKey + "] Checkpoint saved to Redis");
        } catch (Exception e) {
            LOG.warning("[" + checkpointKey + "] Failed to save checkpoint to Redis: " + e.getMessage());
        }
    }

    @Override
    public void clearCheckpoint(String checkpointKey) {
        if (!initialized) {
            LOG.warning("[" + checkpointKey + "] Redis not available - checkpoint not cleared");
            return;
        }

        try {
            keyCommands.del(CHECKPOINT_PREFIX + checkpointKey);
            LOG.info("[" + checkpointKey + "] Checkpoint cleared from Redis");
        } catch (Exception e) {
            LOG.warning("[" + checkpointKey + "] Failed to clear checkpoint from Redis: " + e.getMessage());
        }
    }
}
