package tech.flowcatalyst.postbox.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.postbox.config.PostboxPollerConfig;

@ApplicationScoped
public class PollerScheduler {

    private static final Logger log = Logger.getLogger(PollerScheduler.class);

    @Inject
    PollerDiscovery pollerDiscovery;

    @Inject
    PostboxPollerConfig config;

    /**
     * Run all active partition pollers on the configured poll interval
     */
    @Scheduled(
            every = "${postbox.poller.poll-interval-ms:5000}ms",
            delayed = "${postbox.poller.poll-interval-ms:5000}ms",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP
    )
    public void runAllPollers() {
        try {
            pollerDiscovery.getActivePollers().parallelStream()
                    .forEach(this::safelyPollPartition);
        } catch (Exception e) {
            log.errorf(e, "Error running all pollers");
        }
    }

    /**
     * Safely poll a partition, catching any exceptions
     */
    private void safelyPollPartition(PartitionPoller poller) {
        try {
            poller.poll();
        } catch (Exception e) {
            log.errorf(e, "Error polling partition %s", poller);
        }
    }

}
