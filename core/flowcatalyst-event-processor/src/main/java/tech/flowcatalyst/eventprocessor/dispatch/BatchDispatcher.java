package tech.flowcatalyst.eventprocessor.dispatch;

import io.quarkus.runtime.Quarkus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.bson.Document;
import tech.flowcatalyst.eventprocessor.config.EventProcessorConfig;
import tech.flowcatalyst.eventprocessor.projection.IdempotentBatchWriter;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Dispatches batches of events for processing using virtual threads.
 *
 * Uses a semaphore to limit concurrency - each batch is processed by a
 * separate virtual thread, but we limit how many can run at once.
 *
 * Batches are assigned sequential numbers for checkpoint tracking.
 */
@ApplicationScoped
public class BatchDispatcher {

    private static final Logger LOG = Logger.getLogger(BatchDispatcher.class.getName());

    @Inject
    EventProcessorConfig config;

    @Inject
    IdempotentBatchWriter writer;

    @Inject
    CheckpointTracker checkpointTracker;

    private Semaphore concurrencyLimit;
    private final AtomicLong batchSequence = new AtomicLong(0);

    @PostConstruct
    void init() {
        concurrencyLimit = new Semaphore(config.concurrency());
        LOG.info("BatchDispatcher initialized with concurrency limit: " + config.concurrency());
    }

    /**
     * Dispatch a batch of events for processing.
     *
     * This method blocks if we're at max concurrency until a slot is available.
     *
     * @param events      the events to process
     * @param resumeToken the change stream resume token for checkpoint
     */
    public void dispatch(List<Document> events, BsonDocument resumeToken) {
        if (events.isEmpty()) {
            return;
        }

        // Check if we've had a fatal error
        if (checkpointTracker.hasFatalError()) {
            LOG.warning("Skipping batch dispatch - fatal error has occurred");
            return;
        }

        long seq = batchSequence.incrementAndGet();

        try {
            // Block if at max concurrency - this provides backpressure
            concurrencyLimit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning("Interrupted while waiting for concurrency slot");
            return;
        }

        // Spawn virtual thread to process the batch
        Thread.startVirtualThread(() -> {
            try {
                processBatch(seq, events, resumeToken);
            } finally {
                concurrencyLimit.release();
            }
        });
    }

    /**
     * Process a batch of events.
     */
    private void processBatch(long seq, List<Document> events, BsonDocument resumeToken) {
        try {
            writer.writeBatch(events);
            checkpointTracker.markComplete(seq, resumeToken);
            LOG.fine("Batch " + seq + " completed (" + events.size() + " events)");
        } catch (Exception e) {
            LOG.severe("Batch " + seq + " failed: " + e.getMessage());
            checkpointTracker.markFailed(seq, e);

            // Fatal error - trigger shutdown to let standby take over
            LOG.severe("Fatal error in batch processing - triggering shutdown");
            triggerShutdown();
        }
    }

    /**
     * Trigger application shutdown.
     * This allows hot standby to take over.
     */
    private void triggerShutdown() {
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Brief delay to flush logs
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Quarkus.asyncExit(1);
        }, "event-processor-shutdown-thread").start();
    }

    /**
     * Get the current batch sequence number.
     */
    public long getCurrentSequence() {
        return batchSequence.get();
    }

    /**
     * Get the number of available concurrency slots.
     */
    public int getAvailableSlots() {
        return concurrencyLimit.availablePermits();
    }
}
