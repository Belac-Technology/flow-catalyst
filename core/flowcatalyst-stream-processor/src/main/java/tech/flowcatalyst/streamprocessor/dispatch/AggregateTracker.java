package tech.flowcatalyst.streamprocessor.dispatch;

import org.bson.BsonDocument;
import org.bson.Document;

import java.util.*;
import java.util.logging.Logger;

/**
 * Tracks which aggregate IDs are currently in-flight to prevent concurrent processing.
 *
 * <p>When multiple batches are processed in parallel, this tracker ensures that
 * documents with the same aggregate ID are never in concurrent batches. This
 * prevents race conditions where updates to the same entity could be applied
 * out of order.</p>
 *
 * <p>Example:</p>
 * <ul>
 *   <li>Batch 1 contains Order-123 update 1</li>
 *   <li>While Batch 1 is processing, Order-123 update 2 arrives</li>
 *   <li>Update 2 is queued in pending, not added to Batch 2</li>
 *   <li>When Batch 1 completes, Order-123 is released and update 2 can proceed</li>
 * </ul>
 *
 * <p>Note: This class is NOT a CDI bean. Each stream gets its own instance
 * created by the StreamProcessorStarter.</p>
 */
public class AggregateTracker {

    private static final Logger LOG = Logger.getLogger(AggregateTracker.class.getName());

    private final String streamName;
    private final Object lock = new Object();

    // Aggregate IDs currently being processed (batch_seq -> set of aggregate IDs)
    private final Map<Long, Set<String>> inFlight = new HashMap<>();

    // Documents waiting for their aggregate to be free
    private final List<PendingDocument> pending = new ArrayList<>();

    /**
     * A document that is waiting for its aggregate ID to be released.
     */
    public record PendingDocument(
            String aggregateId,
            Document document,
            BsonDocument resumeToken
    ) {}

    /**
     * Create a new aggregate tracker for a stream.
     *
     * @param streamName name of the stream (for logging)
     */
    public AggregateTracker(String streamName) {
        this.streamName = streamName;
    }

    /**
     * Check if an aggregate ID is currently in-flight (being processed in any batch).
     *
     * @param aggregateId the aggregate ID to check
     * @return true if the aggregate is currently being processed
     */
    public boolean isInFlight(String aggregateId) {
        if (aggregateId == null) {
            return false;
        }
        synchronized (lock) {
            return inFlight.values().stream()
                    .anyMatch(ids -> ids.contains(aggregateId));
        }
    }

    /**
     * Register aggregate IDs for a batch that is about to be processed.
     *
     * @param batchSeq     the batch sequence number
     * @param aggregateIds the set of aggregate IDs in this batch
     */
    public void registerBatch(long batchSeq, Set<String> aggregateIds) {
        synchronized (lock) {
            inFlight.put(batchSeq, new HashSet<>(aggregateIds));
            LOG.fine("[" + streamName + "] Registered batch " + batchSeq +
                    " with " + aggregateIds.size() + " aggregate IDs");
        }
    }

    /**
     * Mark a batch as complete, releasing its aggregate IDs.
     *
     * <p>Any pending documents whose aggregate ID is now free will be returned.
     * These documents are removed from the pending queue but NOT automatically
     * re-queued - the change stream cursor will replay them.</p>
     *
     * @param batchSeq the batch sequence number that completed
     * @return list of pending documents that are now ready (their aggregates are free)
     */
    public List<PendingDocument> completeBatch(long batchSeq) {
        synchronized (lock) {
            // Remove this batch's aggregate IDs
            Set<String> removed = inFlight.remove(batchSeq);
            if (removed == null) {
                return Collections.emptyList();
            }

            // Check which pending documents are now free
            List<PendingDocument> ready = new ArrayList<>();
            List<PendingDocument> stillPending = new ArrayList<>();

            for (PendingDocument doc : pending) {
                boolean isBlocked = inFlight.values().stream()
                        .anyMatch(ids -> ids.contains(doc.aggregateId()));

                if (isBlocked) {
                    stillPending.add(doc);
                } else {
                    ready.add(doc);
                }
            }

            pending.clear();
            pending.addAll(stillPending);

            if (!ready.isEmpty()) {
                LOG.fine("[" + streamName + "] Batch " + batchSeq +
                        " completed, released " + ready.size() + " pending documents");
            }

            return ready;
        }
    }

    /**
     * Add a document to the pending queue (blocked by in-flight aggregate).
     *
     * @param doc the pending document
     */
    public void addPending(PendingDocument doc) {
        synchronized (lock) {
            pending.add(doc);
            LOG.fine("[" + streamName + "] Added pending document for aggregate " +
                    doc.aggregateId() + " (queue size: " + pending.size() + ")");
        }
    }

    /**
     * Get the number of pending documents.
     */
    public int getPendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /**
     * Get the number of in-flight batches.
     */
    public int getInFlightBatchCount() {
        synchronized (lock) {
            return inFlight.size();
        }
    }

    /**
     * Get the stream name.
     */
    public String getStreamName() {
        return streamName;
    }

    /**
     * Reset state (for testing).
     */
    public void reset() {
        synchronized (lock) {
            inFlight.clear();
            pending.clear();
        }
    }
}
