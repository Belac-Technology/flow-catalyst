package tech.flowcatalyst.streamprocessor.stream;

import tech.flowcatalyst.streamprocessor.config.StreamConfig;
import tech.flowcatalyst.streamprocessor.dispatch.BatchDispatcher;
import tech.flowcatalyst.streamprocessor.dispatch.CheckpointTracker;
import tech.flowcatalyst.streamprocessor.mapper.ProjectionMapper;
import tech.flowcatalyst.streamprocessor.projection.ProjectionWriter;

/**
 * Runtime context for a single stream instance.
 *
 * <p>Groups all components needed to process one stream, including
 * the watcher, dispatcher, writer, and checkpoint tracker.</p>
 *
 * @param name              stream name (e.g., "events", "dispatch-jobs")
 * @param config            stream configuration
 * @param mapper            projection mapper for transforming documents
 * @param watcher           change stream watcher
 * @param dispatcher        batch dispatcher
 * @param writer            projection writer
 * @param checkpointTracker checkpoint tracker
 * @param checkpointKey     key for storing checkpoints
 */
public record StreamContext(
        String name,
        StreamConfig config,
        ProjectionMapper mapper,
        StreamWatcher watcher,
        BatchDispatcher dispatcher,
        ProjectionWriter writer,
        CheckpointTracker checkpointTracker,
        String checkpointKey
) {

    /**
     * Check if this stream is running.
     */
    public boolean isRunning() {
        return watcher.isRunning();
    }

    /**
     * Start this stream.
     */
    public void start() {
        watcher.start();
    }

    /**
     * Stop this stream.
     */
    public void stop() {
        watcher.stop();
    }

    /**
     * Check if this stream has encountered a fatal error.
     */
    public boolean hasFatalError() {
        return checkpointTracker.hasFatalError();
    }

    /**
     * Get the fatal error if one occurred.
     */
    public Exception getFatalError() {
        return checkpointTracker.getFatalError();
    }

    /**
     * Get the current batch sequence number.
     */
    public long getCurrentBatchSequence() {
        return dispatcher.getCurrentSequence();
    }

    /**
     * Get the last checkpointed batch sequence.
     */
    public long getLastCheckpointedSequence() {
        return checkpointTracker.getLastCheckpointedSeq();
    }

    /**
     * Get the number of batches currently in flight.
     */
    public int getInFlightBatchCount() {
        return checkpointTracker.getInFlightCount();
    }

    /**
     * Get the number of available concurrency slots.
     */
    public int getAvailableConcurrencySlots() {
        return dispatcher.getAvailableSlots();
    }
}
