package tech.flowcatalyst.eventprocessor.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for the Event Stream Processor.
 */
@ConfigMapping(prefix = "event-processor")
public interface EventProcessorConfig {

    /**
     * Enable/disable the event processor.
     * When disabled, the processor won't start even if start() is called.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Maximum number of concurrent batch processors (virtual threads).
     * Each batch is processed by a separate virtual thread.
     */
    @WithDefault("10")
    int concurrency();

    /**
     * Maximum number of events per batch.
     * Batches are flushed when this size is reached.
     */
    @WithDefault("100")
    int batchMaxSize();

    /**
     * Maximum time to wait before flushing an incomplete batch (in milliseconds).
     * Batches are flushed when this timeout is reached even if not full.
     */
    @WithDefault("100")
    long batchMaxWaitMs();

    /**
     * MongoDB database name.
     */
    @WithDefault("flowcatalyst")
    String database();

    /**
     * Source collection to watch for events.
     */
    @WithDefault("events")
    String sourceCollection();

    /**
     * Target collection for projected events.
     * This collection will have more indexes for query optimization.
     */
    @WithDefault("events_read")
    String projectionCollection();

    /**
     * Redis key for storing the change stream checkpoint.
     */
    @WithDefault("event-processor-checkpoint")
    String checkpointKey();
}
