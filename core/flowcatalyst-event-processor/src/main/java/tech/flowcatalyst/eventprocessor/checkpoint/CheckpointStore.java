package tech.flowcatalyst.eventprocessor.checkpoint;

import org.bson.BsonDocument;

import java.util.Optional;

/**
 * Interface for storing and retrieving change stream checkpoints.
 *
 * The checkpoint is a MongoDB resume token that allows the change stream
 * to resume from where it left off after a restart.
 */
public interface CheckpointStore {

    /**
     * Get the stored checkpoint (resume token).
     *
     * @return Optional containing the resume token, empty if no checkpoint exists
     * @throws CheckpointUnavailableException if the checkpoint store cannot be reached
     */
    Optional<BsonDocument> getCheckpoint() throws CheckpointUnavailableException;

    /**
     * Save a checkpoint (resume token).
     *
     * @param resumeToken the MongoDB change stream resume token
     */
    void saveCheckpoint(BsonDocument resumeToken);

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
