package tech.flowcatalyst.streamprocessor.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Map;

/**
 * Root configuration for the Stream Processor.
 *
 * <p>The stream processor watches MongoDB change streams and writes projections
 * to read-optimized collections. Multiple streams can be configured, each with
 * its own source/target collections and projection mapper.</p>
 *
 * <p>Example configuration:</p>
 * <pre>
 * # Global settings
 * stream-processor.enabled=true
 * stream-processor.database=flowcatalyst
 *
 * # Events stream
 * stream-processor.streams.events.source-collection=events
 * stream-processor.streams.events.projection-collection=events_read
 * stream-processor.streams.events.mapper=events
 *
 * # Dispatch Jobs stream
 * stream-processor.streams.dispatch-jobs.source-collection=dispatch_jobs
 * stream-processor.streams.dispatch-jobs.projection-collection=dispatch_jobs_read
 * stream-processor.streams.dispatch-jobs.mapper=dispatch-jobs
 * stream-processor.streams.dispatch-jobs.watch-operations=insert,update
 * </pre>
 */
@ConfigMapping(prefix = "stream-processor")
public interface StreamProcessorConfig {

    /**
     * Global enable/disable for the stream processor.
     * When disabled, no streams will start even if individually enabled.
     * Defaults to false.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * MongoDB database name (shared by all streams).
     * Defaults to "flowcatalyst".
     */
    @WithDefault("flowcatalyst")
    String database();

    /**
     * Named stream configurations.
     *
     * <p>The key is the stream name (e.g., "events", "dispatch-jobs").
     * Each stream has its own configuration for source/target collections,
     * mapper, concurrency, and batch settings.</p>
     *
     * @return map of stream name to configuration
     */
    Map<String, StreamConfig> streams();
}
