package tech.flowcatalyst.streamprocessor.checkpoint;

import org.bson.BsonDocument;

import java.util.Optional;

/**
 * Interface for storing and retrieving change stream checkpoints.
 *
 * <p>The checkpoint is a MongoDB resume token that allows the change stream
 * to resume from where it left off after a restart.</p>
 *
 * <p>This interface supports multiple streams by accepting a checkpoint key
 * parameter for each operation.</p>
 */
public interface CheckpointStore {

    /**
     * Get the stored checkpoint (resume token) for a specific stream.
     *
     * @param checkpointKey unique key identifying the stream's checkpoint
     * @return Optional containing the resume token, empty if no checkpoint exists
     * @throws CheckpointUnavailableException if the checkpoint store cannot be reached
     */
    Optional<BsonDocument> getCheckpoint(String checkpointKey) throws CheckpointUnavailableException;

    /**
     * Save a checkpoint (resume token) for a specific stream.
     *
     * @param checkpointKey unique key identifying the stream's checkpoint
     * @param resumeToken   the MongoDB change stream resume token
     */
    void saveCheckpoint(String checkpointKey, BsonDocument resumeToken);

    /**
     * Clear a checkpoint (for recovery from stale resume token).
     * The default implementation does nothing - durable stores should override.
     *
     * @param checkpointKey unique key identifying the stream's checkpoint
     */
    default void clearCheckpoint(String checkpointKey) {
        // Default no-op for in-memory stores
    }

    /**
     * Exception thrown when the checkpoint store cannot be reached.
     * This is distinct from "no checkpoint exists" - it means we cannot
     * determine if a checkpoint exists or not.
     */
    class CheckpointUnavailableException extends Exception {
        public CheckpointUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public CheckpointUnavailableException(String message) {
            super(message);
        }
    }
}
