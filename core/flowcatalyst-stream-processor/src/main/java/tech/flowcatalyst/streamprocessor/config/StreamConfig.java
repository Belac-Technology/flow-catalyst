package tech.flowcatalyst.streamprocessor.config;

import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

/**
 * Configuration for a single stream.
 *
 * <p>Each stream watches a MongoDB change stream on a source collection
 * and writes projections to a target collection using a named mapper.</p>
 *
 * <p>Example configuration:</p>
 * <pre>
 * stream-processor.streams.events.enabled=true
 * stream-processor.streams.events.source-collection=events
 * stream-processor.streams.events.projection-collection=events_read
 * stream-processor.streams.events.mapper=events
 * stream-processor.streams.events.watch-operations=insert
 * stream-processor.streams.events.concurrency=10
 * </pre>
 */
public interface StreamConfig {

    /**
     * Enable/disable this specific stream.
     * Defaults to true.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Source collection to watch for changes.
     * Required.
     */
    String sourceCollection();

    /**
     * Target collection for projected documents.
     * Required.
     */
    String projectionCollection();

    /**
     * CDI bean name for the projection mapper.
     * Must match a {@code @Named} bean implementing {@code ProjectionMapper}.
     * Required.
     */
    String mapper();

    /**
     * Redis/checkpoint key for this stream.
     * Defaults to "{streamName}-checkpoint".
     */
    Optional<String> checkpointKey();

    /**
     * MongoDB change stream operations to watch.
     * Comma-separated list: insert, update, replace.
     * Defaults to "insert".
     */
    @WithDefault("insert")
    List<String> watchOperations();

    /**
     * Maximum concurrent batch processors (virtual threads).
     * Defaults to 10.
     */
    @WithDefault("10")
    int concurrency();

    /**
     * Maximum documents per batch before flush.
     * Defaults to 100.
     */
    @WithDefault("100")
    int batchMaxSize();

    /**
     * Maximum time to wait before flushing an incomplete batch (milliseconds).
     * Defaults to 100ms.
     */
    @WithDefault("100")
    long batchMaxWaitMs();

    /**
     * Field to use as aggregate ID for ordering guarantees.
     * Documents with the same aggregate ID are never processed concurrently
     * across different batches. This prevents race conditions when multiple
     * updates to the same entity arrive in quick succession.
     * Defaults to "_id".
     */
    @WithDefault("_id")
    String aggregateIdField();
}
