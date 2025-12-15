package tech.flowcatalyst.outbox.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Configuration for the outbox processor.
 */
@ConfigMapping(prefix = "outbox-processor")
public interface OutboxProcessorConfig {

    /**
     * Whether the outbox processor is enabled.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Interval between polling cycles.
     * Supports duration format: 1s, 500ms, etc.
     */
    @WithDefault("1s")
    String pollInterval();

    /**
     * Maximum number of items to fetch per poll cycle.
     */
    @WithDefault("500")
    int pollBatchSize();

    /**
     * Maximum number of items to send in a single API batch request.
     */
    @WithDefault("100")
    int apiBatchSize();

    /**
     * Maximum number of message groups that can be processed concurrently.
     */
    @WithDefault("10")
    int maxConcurrentGroups();

    /**
     * Size of the in-memory buffer between poller and processors.
     */
    @WithDefault("1000")
    int globalBufferSize();

    /**
     * Database type to connect to: POSTGRESQL, MYSQL, or MONGODB.
     */
    @WithDefault("POSTGRESQL")
    DatabaseType databaseType();

    /**
     * Table/collection name for events outbox.
     */
    @WithDefault("outbox_events")
    String eventsTable();

    /**
     * Table/collection name for dispatch jobs outbox.
     */
    @WithDefault("outbox_dispatch_jobs")
    String dispatchJobsTable();

    /**
     * FlowCatalyst API base URL.
     */
    String apiBaseUrl();

    /**
     * Optional Bearer token for FlowCatalyst API authentication.
     */
    Optional<String> apiToken();

    /**
     * Maximum number of retry attempts for failed items.
     */
    @WithDefault("3")
    int maxRetries();

    /**
     * Delay in seconds before retrying failed items.
     */
    @WithDefault("60")
    int retryDelaySeconds();

    /**
     * Timeout in seconds for items stuck in PROCESSING status (crash recovery).
     */
    @WithDefault("300")
    int processingTimeoutSeconds();

    /**
     * MongoDB database name (only used when databaseType=MONGODB).
     */
    @WithDefault("outbox")
    String mongoDatabase();
}
