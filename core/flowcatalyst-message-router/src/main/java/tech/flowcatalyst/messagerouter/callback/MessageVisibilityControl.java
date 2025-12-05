package tech.flowcatalyst.messagerouter.callback;

import tech.flowcatalyst.messagerouter.model.MessagePointer;

/**
 * Optional interface for message callbacks that support visibility timeout control.
 * Allows fast-fail scenarios (rate limit, pool full) to retry quickly (1 second)
 * while real processing failures use the default visibility timeout.
 *
 * <p>Implementations:
 * <ul>
 *   <li>SQS: Use ChangeMessageVisibility API</li>
 *   <li>ActiveMQ: No-op (doesn't support visibility control)</li>
 * </ul>
 */
public interface MessageVisibilityControl {

    /**
     * Set message visibility to 1 second for fast retry on transient failures.
     * Used when message couldn't be processed due to:
     * - Rate limiting
     * - Pool queue full
     *
     * @param message the message to adjust visibility for
     */
    void setFastFailVisibility(MessagePointer message);

    /**
     * Reset message visibility to default (typically 30+ seconds) for real processing failures.
     * Used when message was processed but failed due to:
     * - Downstream service error (4xx, 5xx)
     * - Connection timeout
     * - Business logic failure
     *
     * @param message the message to adjust visibility for
     */
    void resetVisibilityToDefault(MessagePointer message);

    /**
     * Extend message visibility timeout to keep message invisible while processing continues.
     * This prevents message redelivery when processing takes longer than the initial visibility timeout.
     * Should be called periodically (e.g., every 15 seconds) while message is being processed.
     *
     * @param message the message to extend visibility for
     * @param visibilityTimeoutSeconds how long to keep the message invisible (typically 30-60 seconds)
     */
    void extendVisibility(MessagePointer message, int visibilityTimeoutSeconds);
}
