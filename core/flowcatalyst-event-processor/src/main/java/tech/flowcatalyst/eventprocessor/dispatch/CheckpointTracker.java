package tech.flowcatalyst.eventprocessor.dispatch;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import tech.flowcatalyst.eventprocessor.checkpoint.CheckpointStore;

import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Tracks in-flight batch processing and manages checkpoint advancement.
 *
 * Batches are processed concurrently, but checkpoints can only be advanced
 * when all prior batches have completed. This ensures we don't skip events
 * on restart.
 *
 * Example:
 * - Batches 1, 2, 3 dispatched
 * - Batch 3 completes -> checkpoint stays at 0 (waiting for 1, 2)
 * - Batch 1 completes -> checkpoint advances to 1
 * - Batch 2 completes -> checkpoint advances to 2, then 3
 */
@ApplicationScoped
public class CheckpointTracker {

    private static final Logger LOG = Logger.getLogger(CheckpointTracker.class.getName());

    @Inject
    CheckpointStore checkpointStore;

    private final TreeMap<Long, BatchResult> batches = new TreeMap<>();
    private long lastCheckpointedSeq = 0;
    private final Object lock = new Object();

    // Track if we've had a fatal error
    private volatile Exception fatalError = null;

    /**
     * Mark a batch as successfully completed.
     *
     * @param seq         the batch sequence number
     * @param resumeToken the change stream resume token for this batch
     */
    public void markComplete(long seq, BsonDocument resumeToken) {
        synchronized (lock) {
            batches.put(seq, new BatchResult(resumeToken, true, null));
            advanceCheckpoint();
        }
    }

    /**
     * Mark a batch as failed.
     *
     * @param seq   the batch sequence number
     * @param error the error that caused the failure
     */
    public void markFailed(long seq, Exception error) {
        synchronized (lock) {
            batches.put(seq, new BatchResult(null, false, error));
            this.fatalError = error;
            // Don't advance checkpoint - we're failing
        }
    }

    /**
     * Check if a fatal error has occurred.
     */
    public boolean hasFatalError() {
        return fatalError != null;
    }

    /**
     * Get the fatal error if one occurred.
     */
    public Exception getFatalError() {
        return fatalError;
    }

    /**
     * Advance the checkpoint to the highest contiguous completed batch.
     */
    private void advanceCheckpoint() {
        while (batches.containsKey(lastCheckpointedSeq + 1)) {
            BatchResult result = batches.get(lastCheckpointedSeq + 1);
            if (!result.success()) {
                break; // Stop at failed batch
            }

            lastCheckpointedSeq++;
            BsonDocument token = batches.remove(lastCheckpointedSeq).resumeToken();

            // Persist checkpoint
            checkpointStore.saveCheckpoint(token);
            LOG.fine("Checkpoint advanced to batch " + lastCheckpointedSeq);
        }
    }

    /**
     * Get the number of batches currently in flight.
     */
    public int getInFlightCount() {
        synchronized (lock) {
            return batches.size();
        }
    }

    /**
     * Get the last checkpointed sequence number.
     */
    public long getLastCheckpointedSeq() {
        return lastCheckpointedSeq;
    }

    /**
     * Reset state (for testing).
     */
    void reset() {
        synchronized (lock) {
            batches.clear();
            lastCheckpointedSeq = 0;
            fatalError = null;
        }
    }

    private record BatchResult(BsonDocument resumeToken, boolean success, Exception error) {}
}
