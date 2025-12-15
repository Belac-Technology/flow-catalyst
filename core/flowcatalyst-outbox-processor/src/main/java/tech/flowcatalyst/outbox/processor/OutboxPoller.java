package tech.flowcatalyst.outbox.processor;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.outbox.config.OutboxProcessorConfig;
import tech.flowcatalyst.outbox.model.OutboxItem;
import tech.flowcatalyst.outbox.model.OutboxItemType;
import tech.flowcatalyst.outbox.repository.OutboxRepository;
import tech.flowcatalyst.standby.StandbyService;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled service that polls the outbox tables for pending items.
 * Only runs on the primary instance when hot standby is enabled.
 */
@ApplicationScoped
public class OutboxPoller {

    private static final Logger LOG = Logger.getLogger(OutboxPoller.class);

    @Inject
    StandbyService standbyService;

    @Inject
    OutboxProcessorConfig config;

    @Inject
    OutboxRepository repository;

    @Inject
    GlobalBuffer globalBuffer;

    private final AtomicBoolean polling = new AtomicBoolean(false);

    /**
     * Main polling loop - runs at configured interval.
     * Polls both events and dispatch jobs tables.
     */
    @Scheduled(every = "${outbox-processor.poll-interval:1s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!config.enabled()) {
            return;
        }

        if (!standbyService.isPrimary()) {
            LOG.trace("Not primary instance, skipping poll");
            return;
        }

        if (!polling.compareAndSet(false, true)) {
            LOG.debug("Previous poll still in progress, skipping");
            return;
        }

        try {
            // Poll events
            List<OutboxItem> events = repository.fetchAndLockPending(
                OutboxItemType.EVENT,
                config.pollBatchSize()
            );
            if (!events.isEmpty()) {
                int rejected = globalBuffer.addAll(events);
                LOG.debugf("Polled %d events, %d rejected (buffer full)", events.size(), rejected);
            }

            // Poll dispatch jobs
            List<OutboxItem> jobs = repository.fetchAndLockPending(
                OutboxItemType.DISPATCH_JOB,
                config.pollBatchSize()
            );
            if (!jobs.isEmpty()) {
                int rejected = globalBuffer.addAll(jobs);
                LOG.debugf("Polled %d dispatch jobs, %d rejected (buffer full)", jobs.size(), rejected);
            }

            if (!events.isEmpty() || !jobs.isEmpty()) {
                LOG.infof("Polled %d events, %d dispatch jobs (buffer: %d/%d)",
                    events.size(), jobs.size(),
                    globalBuffer.getBufferSize(), globalBuffer.getBufferCapacity());
            }

        } catch (Exception e) {
            LOG.errorf(e, "Error during poll cycle");
        } finally {
            polling.set(false);
        }
    }

    /**
     * Crash recovery - runs periodically to recover stuck items.
     * Items stuck in PROCESSING status are reset to PENDING.
     */
    @Scheduled(every = "1m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void recoverStuckItems() {
        if (!config.enabled() || !standbyService.isPrimary()) {
            return;
        }

        try {
            int recoveredEvents = repository.recoverStuckItems(
                OutboxItemType.EVENT,
                config.processingTimeoutSeconds()
            );
            int recoveredJobs = repository.recoverStuckItems(
                OutboxItemType.DISPATCH_JOB,
                config.processingTimeoutSeconds()
            );

            if (recoveredEvents > 0 || recoveredJobs > 0) {
                LOG.infof("Recovered %d stuck events, %d stuck dispatch jobs", recoveredEvents, recoveredJobs);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Error during stuck items recovery");
        }
    }
}
