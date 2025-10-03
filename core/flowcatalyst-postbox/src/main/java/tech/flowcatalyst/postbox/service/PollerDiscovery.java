package tech.flowcatalyst.postbox.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.postbox.config.PostboxPollerConfig;
import tech.flowcatalyst.postbox.metrics.PostboxMetrics;
import tech.flowcatalyst.postbox.repository.PostboxMessageRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PollerDiscovery {

    private static final Logger log = Logger.getLogger(PollerDiscovery.class);

    @Inject
    PostboxMessageRepository repository;

    @Inject
    PostboxPollerConfig config;

    @Inject
    PostboxMetrics metrics;

    // Map of "tenantId:partitionId" -> PartitionPoller instance
    private final Map<String, PartitionPoller> activePollers = new ConcurrentHashMap<>();

    /**
     * Discover active partitions and manage poller lifecycle
     * Runs every X milliseconds (configured by discovery-interval-ms)
     */
    @Scheduled(
            every = "${postbox.poller.discovery-interval-ms:300000}ms",
            delayed = "${postbox.poller.discovery-interval-ms:300000}ms"
    )
    public void discoverAndManagePollers() {
        try {
            log.debug("Starting partition discovery");

            // Calculate cutoff time for "active" partitions
            Instant cutoffTime = Instant.now()
                    .minus(config.inactiveWindowDays(), ChronoUnit.DAYS);

            // Find all active tenant:partition pairs
            List<Object[]> activePartitions = repository.findActivePartitions(cutoffTime);
            Set<String> activePartitionKeys = new HashSet<>();

            for (Object[] row : activePartitions) {
                Long tenantId = (Long) row[0];
                String partitionId = (String) row[1];
                String key = tenantId + ":" + partitionId;
                activePartitionKeys.add(key);

                // Start new poller if not already running
                if (!activePollers.containsKey(key)) {
                    startPoller(tenantId, partitionId, key);
                }
            }

            // Stop pollers for inactive partitions
            activePollers.keySet().stream()
                    .filter(key -> !activePartitionKeys.contains(key))
                    .forEach(this::stopPoller);

            // Record poller count for metrics
            metrics.recordPollerCount(activePollers.size());

            log.debugf("Discovery complete: %d active pollers", activePollers.size());
        } catch (Exception e) {
            log.errorf(e, "Error in partition discovery");
        }
    }

    private void startPoller(Long tenantId, String partitionId, String key) {
        try {
            PartitionPoller poller = new PartitionPoller(tenantId, partitionId);
            activePollers.put(key, poller);
            log.infof("Started poller for tenant=%d, partition=%s", tenantId, partitionId);

            // Schedule the poller to run on the configured interval
            // This is handled by @Scheduled on the poller's poll() method
        } catch (Exception e) {
            log.errorf(e, "Failed to start poller for tenant=%d, partition=%s", tenantId, partitionId);
        }
    }

    private void stopPoller(String key) {
        try {
            PartitionPoller poller = activePollers.remove(key);
            if (poller != null) {
                log.infof("Stopped poller: %s", poller);
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to stop poller with key: %s", key);
        }
    }

    /**
     * Get all currently active pollers (for monitoring/debugging)
     */
    public Collection<PartitionPoller> getActivePollers() {
        return Collections.unmodifiableCollection(activePollers.values());
    }

    /**
     * Get poller count for monitoring
     */
    public int getPollerCount() {
        return activePollers.size();
    }

    /**
     * Find a specific poller by tenant and partition
     */
    public PartitionPoller getPoller(Long tenantId, String partitionId) {
        return activePollers.get(tenantId + ":" + partitionId);
    }

}
