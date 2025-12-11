package tech.flowcatalyst.eventprocessor.checkpoint;

import org.bson.BsonDocument;

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
     * @return the resume token, or null if no checkpoint exists
     */
    BsonDocument getCheckpoint();

    /**
     * Save a checkpoint (resume token).
     *
     * @param resumeToken the MongoDB change stream resume token
     */
    void saveCheckpoint(BsonDocument resumeToken);
}
