package tech.flowcatalyst.postbox.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "postbox.poller")
public interface PostboxPollerConfig {

    /**
     * Discovery interval in milliseconds (default: 5 minutes)
     * How often to scan for new/stale partitions
     */
    @WithDefault("300000")
    Long discoveryIntervalMs();

    /**
     * Inactive window in days (default: 3)
     * Partitions without activity in this window will stop being polled
     */
    @WithDefault("3")
    Integer inactiveWindowDays();

    /**
     * Individual poller poll interval in milliseconds (default: 5 seconds)
     * How often each partition poller checks for pending messages
     */
    @WithDefault("5000")
    Long pollIntervalMs();

    /**
     * Batch size (default: 100)
     * Number of pending messages to fetch per poll
     */
    @WithDefault("100")
    Integer batchSize();

    /**
     * Max retries for 400 client errors (default: 5)
     * Increment retry_count on 400, fail if >= max
     */
    @WithDefault("5")
    Integer maxRetries();

    /**
     * Whether to compress request bodies with gzip (default: true)
     */
    @WithDefault("true")
    Boolean requestGzip();

}
