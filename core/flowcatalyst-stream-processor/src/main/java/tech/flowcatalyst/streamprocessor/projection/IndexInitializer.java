package tech.flowcatalyst.streamprocessor.projection;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import tech.flowcatalyst.streamprocessor.config.StreamConfig;
import tech.flowcatalyst.streamprocessor.config.StreamProcessorConfig;
import tech.flowcatalyst.streamprocessor.mapper.IndexDefinition;
import tech.flowcatalyst.streamprocessor.mapper.ProjectionMapper;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Initializes MongoDB indexes for projection collections.
 *
 * <p>Creates indexes defined by each stream's {@link ProjectionMapper}.
 * Index creation is idempotent - existing indexes are not recreated.</p>
 *
 * <p>This is a utility class, not a CDI bean. It's called by
 * StreamProcessorStarter during startup.</p>
 */
public class IndexInitializer {

    private static final Logger LOG = Logger.getLogger(IndexInitializer.class.getName());

    private final MongoClient mongoClient;
    private final StreamProcessorConfig config;

    /**
     * Create an index initializer.
     *
     * @param mongoClient MongoDB client
     * @param config      stream processor configuration
     */
    public IndexInitializer(MongoClient mongoClient, StreamProcessorConfig config) {
        this.mongoClient = mongoClient;
        this.config = config;
    }

    /**
     * Initialize indexes for all enabled streams.
     *
     * @param mappers map of stream name to projection mapper
     */
    public void initializeAll(Map<String, ProjectionMapper> mappers) {
        config.streams().forEach((streamName, streamConfig) -> {
            if (!streamConfig.enabled()) {
                LOG.fine("[" + streamName + "] Stream disabled, skipping index initialization");
                return;
            }

            ProjectionMapper mapper = mappers.get(streamName);
            if (mapper == null) {
                LOG.warning("[" + streamName + "] No mapper found, skipping index initialization");
                return;
            }

            initializeStream(streamName, streamConfig, mapper);
        });
    }

    /**
     * Initialize indexes for a single stream.
     *
     * @param streamName   name of the stream
     * @param streamConfig stream configuration
     * @param mapper       projection mapper for this stream
     */
    public void initializeStream(String streamName, StreamConfig streamConfig, ProjectionMapper mapper) {
        String collectionName = streamConfig.projectionCollection();
        LOG.info("[" + streamName + "] Initializing indexes for collection: " + collectionName);

        try {
            MongoCollection<Document> collection = mongoClient
                    .getDatabase(config.database())
                    .getCollection(collectionName);

            List<IndexDefinition> indexes = mapper.getIndexDefinitions();
            int created = 0;
            int skipped = 0;

            for (IndexDefinition index : indexes) {
                if (createIndex(collection, index, streamName)) {
                    created++;
                } else {
                    skipped++;
                }
            }

            LOG.info("[" + streamName + "] Index initialization completed: " + created + " created, " + skipped + " skipped");
        } catch (Exception e) {
            LOG.severe("[" + streamName + "] Failed to initialize indexes: " + e.getMessage());
            // Don't fail startup - indexes can be created manually if needed
        }
    }

    /**
     * Create a single index.
     *
     * @return true if index was created, false if skipped (already exists or error)
     */
    private boolean createIndex(MongoCollection<Document> collection, IndexDefinition index, String streamName) {
        try {
            collection.createIndex(index.keys(), index.options().name(index.name()));
            LOG.fine("[" + streamName + "] Created index: " + index.name());
            return true;
        } catch (Exception e) {
            // Index might already exist with same or different options
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                LOG.fine("[" + streamName + "] Index already exists: " + index.name());
            } else {
                LOG.warning("[" + streamName + "] Could not create index " + index.name() + ": " + e.getMessage());
            }
            return false;
        }
    }
}
