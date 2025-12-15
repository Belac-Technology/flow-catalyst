package tech.flowcatalyst.outbox.repository;

import tech.flowcatalyst.outbox.model.OutboxItem;
import tech.flowcatalyst.outbox.model.OutboxItemType;

import java.util.List;

/**
 * Repository interface for outbox operations.
 * Implementations exist for PostgreSQL, MySQL, and MongoDB.
 */
public interface OutboxRepository {

    /**
     * Fetch pending items and atomically mark them as PROCESSING.
     * Items are returned ordered by messageGroup, then createdAt for FIFO ordering.
     *
     * @param type  The type of items to fetch (EVENT or DISPATCH_JOB)
     * @param limit Maximum number of items to fetch
     * @return List of items now marked as PROCESSING
     */
    List<OutboxItem> fetchAndLockPending(OutboxItemType type, int limit);

    /**
     * Mark items as COMPLETED.
     *
     * @param type The type of items
     * @param ids  List of item IDs to mark as completed
     */
    void markCompleted(OutboxItemType type, List<String> ids);

    /**
     * Mark items as FAILED with an error message.
     *
     * @param type         The type of items
     * @param ids          List of item IDs to mark as failed
     * @param errorMessage The error message to store
     */
    void markFailed(OutboxItemType type, List<String> ids, String errorMessage);

    /**
     * Schedule items for retry by incrementing retry count and setting status back to PENDING.
     * Only items under the max retry limit should be scheduled.
     *
     * @param type The type of items
     * @param ids  List of item IDs to schedule for retry
     */
    void scheduleRetry(OutboxItemType type, List<String> ids);

    /**
     * Recover items stuck in PROCESSING status (crash recovery).
     * Items in PROCESSING longer than the timeout are reset to PENDING.
     *
     * @param type           The type of items
     * @param timeoutSeconds Timeout threshold in seconds
     * @return Number of items recovered
     */
    int recoverStuckItems(OutboxItemType type, int timeoutSeconds);
}
