package tech.flowcatalyst.streamprocessor.dispatch;

import io.quarkus.runtime.Quarkus;
import org.bson.BsonDocument;
import org.bson.Document;
import tech.flowcatalyst.streamprocessor.config.StreamConfig;
import tech.flowcatalyst.streamprocessor.projection.ProjectionWriter;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Dispatches batches of documents for processing using virtual threads.
 *
 * <p>Uses a semaphore to limit concurrency - each batch is processed by a
 * separate virtual thread, but we limit how many can run at once.</p>
 *
 * <p>Batches are assigned sequential numbers for checkpoint tracking.</p>
 *
 * <p>Note: This class is NOT a CDI bean. Each stream gets its own instance
 * created by the StreamProcessorStarter.</p>
 */
public class BatchDispatcher {

    private static final Logger LOG = Logger.getLogger(BatchDispatcher.class.getName());

    private final String streamName;
    private final StreamConfig config;
    private final ProjectionWriter writer;
    private final CheckpointTracker checkpointTracker;
    private final AggregateTracker aggregateTracker;
    private final Semaphore concurrencyLimit;
    private final AtomicLong batchSequence = new AtomicLong(0);

    /**
     * Create a new batch dispatcher for a stream.
     *
     * @param streamName        name of the stream (for logging)
     * @param config            stream configuration
     * @param writer            projection writer for this stream
     * @param checkpointTracker checkpoint tracker for this stream
     * @param aggregateTracker  aggregate tracker for ordering guarantees
     */
    public BatchDispatcher(String streamName, StreamConfig config,
                           ProjectionWriter writer, CheckpointTracker checkpointTracker,
                           AggregateTracker aggregateTracker) {
        this.streamName = streamName;
        this.config = config;
        this.writer = writer;
        this.checkpointTracker = checkpointTracker;
        this.aggregateTracker = aggregateTracker;
        this.concurrencyLimit = new Semaphore(config.concurrency());
        LOG.info("[" + streamName + "] BatchDispatcher initialized with concurrency limit: " + config.concurrency());
    }

    /**
     * Dispatch a batch of documents for processing.
     *
     * <p>This method blocks if we're at max concurrency until a slot is available.</p>
     *
     * @param documents     the documents to process
     * @param aggregateIds  the set of aggregate IDs in this batch (for ordering)
     * @param resumeToken   the change stream resume token for checkpoint
     * @param operationType the type of change stream operation (insert, update, etc.)
     */
    public void dispatch(List<Document> documents, Set<String> aggregateIds,
                         BsonDocument resumeToken, String operationType) {
        if (documents.isEmpty()) {
            return;
        }

        // Check if we've had a fatal error
        if (checkpointTracker.hasFatalError()) {
            LOG.warning("[" + streamName + "] Skipping batch dispatch - fatal error has occurred");
            return;
        }

        long seq = batchSequence.incrementAndGet();

        // Register aggregate IDs for this batch before processing
        aggregateTracker.registerBatch(seq, aggregateIds);

        try {
            // Block if at max concurrency - this provides backpressure
            concurrencyLimit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning("[" + streamName + "] Interrupted while waiting for concurrency slot");
            return;
        }

        // Spawn virtual thread to process the batch
        Thread.startVirtualThread(() -> {
            try {
                processBatch(seq, documents, resumeToken, operationType);
            } finally {
                concurrencyLimit.release();
            }
        });
    }

    /**
     * Process a batch of documents.
     */
    private void processBatch(long seq, List<Document> documents,
                              BsonDocument resumeToken, String operationType) {
        try {
            writer.writeBatch(documents, operationType);
            checkpointTracker.markComplete(seq, resumeToken);
            LOG.fine("[" + streamName + "] Batch " + seq + " completed (" + documents.size() + " documents)");
        } catch (Exception e) {
            LOG.severe("[" + streamName + "] Batch " + seq + " failed: " + e.getMessage());
            checkpointTracker.markFailed(seq, e);

            // Fatal error - trigger shutdown to let standby take over
            LOG.severe("[" + streamName + "] Fatal error in batch processing - triggering shutdown");
            triggerShutdown();
        } finally {
            // Release aggregate IDs and check for pending documents
            List<AggregateTracker.PendingDocument> released = aggregateTracker.completeBatch(seq);
            if (!released.isEmpty()) {
                LOG.fine("[" + streamName + "] Batch " + seq + " completed, " +
                        released.size() + " pending documents released (will be replayed by cursor)");
            }
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
        }, "stream-processor-shutdown-thread").start();
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

    /**
     * Get the stream name.
     */
    public String getStreamName() {
        return streamName;
    }
}
