package tech.flowcatalyst.streamprocessor;

import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import tech.flowcatalyst.streamprocessor.checkpoint.CheckpointStore;
import tech.flowcatalyst.streamprocessor.config.StreamConfig;
import tech.flowcatalyst.streamprocessor.config.StreamProcessorConfig;
import tech.flowcatalyst.streamprocessor.dispatch.AggregateTracker;
import tech.flowcatalyst.streamprocessor.dispatch.BatchDispatcher;
import tech.flowcatalyst.streamprocessor.dispatch.CheckpointTracker;
import tech.flowcatalyst.streamprocessor.mapper.ProjectionMapper;
import tech.flowcatalyst.streamprocessor.projection.IndexInitializer;
import tech.flowcatalyst.streamprocessor.projection.ProjectionWriter;
import tech.flowcatalyst.streamprocessor.stream.StreamContext;
import tech.flowcatalyst.streamprocessor.stream.StreamWatcher;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Programmatic API for starting and stopping the stream processor.
 *
 * <p>Manages multiple streams, each with its own change stream watcher,
 * projection writer, and checkpoint tracker.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * @Inject
 * StreamProcessorStarter streamProcessor;
 *
 * void onStart(@Observes StartupEvent event) {
 *     streamProcessor.start();
 * }
 *
 * void onShutdown(@Observes ShutdownEvent event) {
 *     streamProcessor.stop();
 * }
 * }</pre>
 */
@ApplicationScoped
public class StreamProcessorStarter {

    private static final Logger LOG = Logger.getLogger(StreamProcessorStarter.class.getName());

    @Inject
    StreamProcessorConfig config;

    @Inject
    MongoClient mongoClient;

    @Inject
    CheckpointStore checkpointStore;

    @Inject
    @Any
    Instance<ProjectionMapper> mapperInstances;

    private final Map<String, StreamContext> streams = new ConcurrentHashMap<>();
    private volatile boolean started = false;

    /**
     * Start the stream processor.
     *
     * <p>This method is idempotent - calling it multiple times has no effect
     * after the first successful start.</p>
     *
     * <p>The processor only starts if stream-processor.enabled=true.</p>
     */
    public synchronized void start() {
        if (started) {
            LOG.warning("Stream processor already started");
            return;
        }

        if (!config.enabled()) {
            LOG.info("Stream processor disabled by configuration (stream-processor.enabled=false)");
            return;
        }

        LOG.info("Starting stream processor...");
        LOG.info("Database: " + config.database());

        // Find all mappers
        Map<String, ProjectionMapper> mappers = findMappers();
        LOG.info("Found " + mappers.size() + " projection mappers: " + mappers.keySet());

        // Initialize indexes for all streams
        IndexInitializer indexInitializer = new IndexInitializer(mongoClient, config);
        indexInitializer.initializeAll(mappers);

        // Start each enabled stream
        config.streams().forEach((streamName, streamConfig) -> {
            if (!streamConfig.enabled()) {
                LOG.info("[" + streamName + "] Stream disabled, skipping");
                return;
            }

            ProjectionMapper mapper = mappers.get(streamConfig.mapper());
            if (mapper == null) {
                LOG.severe("[" + streamName + "] No mapper found with name '" + streamConfig.mapper() + "', skipping");
                return;
            }

            StreamContext context = createStreamContext(streamName, streamConfig, mapper);
            streams.put(streamName, context);

            // Start the stream
            context.start();
            LOG.info("[" + streamName + "] Stream started (source: " + streamConfig.sourceCollection() +
                    " -> " + streamConfig.projectionCollection() + ")");
        });

        started = true;
        LOG.info("Stream processor started successfully with " + streams.size() + " stream(s)");
    }

    /**
     * Stop the stream processor gracefully.
     *
     * <p>This method is idempotent - calling it multiple times has no effect.</p>
     */
    public synchronized void stop() {
        if (!started) {
            return;
        }

        LOG.info("Stopping stream processor...");

        streams.values().forEach(context -> {
            try {
                context.stop();
                LOG.info("[" + context.name() + "] Stream stopped");
            } catch (Exception e) {
                LOG.warning("[" + context.name() + "] Error stopping stream: " + e.getMessage());
            }
        });

        streams.clear();
        started = false;

        LOG.info("Stream processor stopped");
    }

    /**
     * Check if the stream processor is currently running.
     */
    public boolean isRunning() {
        return started && !streams.isEmpty() && streams.values().stream().anyMatch(StreamContext::isRunning);
    }

    /**
     * Check if the stream processor is enabled in configuration.
     */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Get all stream contexts.
     */
    public Map<String, StreamContext> getStreams() {
        return Map.copyOf(streams);
    }

    /**
     * Get a specific stream context by name.
     */
    public StreamContext getStream(String name) {
        return streams.get(name);
    }

    /**
     * Find all projection mappers by their @Named qualifier.
     */
    private Map<String, ProjectionMapper> findMappers() {
        Map<String, ProjectionMapper> mappers = new HashMap<>();

        for (ProjectionMapper mapper : mapperInstances) {
            String name = getMapperName(mapper);
            if (name != null) {
                mappers.put(name, mapper);
                LOG.fine("Registered mapper: " + name + " -> " + mapper.getName());
            }
        }

        return mappers;
    }

    /**
     * Get the @Named value for a mapper bean.
     */
    private String getMapperName(ProjectionMapper mapper) {
        Class<?> clazz = mapper.getClass();

        // Check for @Named annotation
        Named named = clazz.getAnnotation(Named.class);
        if (named != null) {
            return named.value();
        }

        // Check superclass (for proxied beans)
        if (clazz.getSuperclass() != null) {
            named = clazz.getSuperclass().getAnnotation(Named.class);
            if (named != null) {
                return named.value();
            }
        }

        // Check interfaces
        for (Annotation annotation : clazz.getAnnotations()) {
            if (annotation.annotationType().getName().contains("Named")) {
                try {
                    return (String) annotation.annotationType().getMethod("value").invoke(annotation);
                } catch (Exception ignored) {
                }
            }
        }

        return null;
    }

    /**
     * Create a stream context with all required components.
     */
    private StreamContext createStreamContext(String streamName, StreamConfig streamConfig, ProjectionMapper mapper) {
        String checkpointKey = streamConfig.checkpointKey().orElse(streamName + "-checkpoint");

        // Create components
        CheckpointTracker checkpointTracker = new CheckpointTracker(checkpointStore, streamName, checkpointKey);
        AggregateTracker aggregateTracker = new AggregateTracker(streamName);

        ProjectionWriter writer = new ProjectionWriter(
                streamName, mongoClient, config, streamConfig, mapper);

        BatchDispatcher dispatcher = new BatchDispatcher(
                streamName, streamConfig, writer, checkpointTracker, aggregateTracker);

        StreamWatcher watcher = new StreamWatcher(
                streamName, mongoClient, config, streamConfig,
                checkpointStore, dispatcher, checkpointTracker, aggregateTracker, checkpointKey);

        return new StreamContext(
                streamName, streamConfig, mapper,
                watcher, dispatcher, writer, checkpointTracker, checkpointKey);
    }
}
