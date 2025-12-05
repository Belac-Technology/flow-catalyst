package tech.flowcatalyst.platform.eventtype.operations;

/**
 * Operation to delete an EventType.
 *
 * Can only delete if:
 * - Status is ARCHIVE, OR
 * - Status is CURRENT but ALL schemas are in FINALISING status (never finalized)
 *
 * @param eventTypeId The EventType to delete
 */
public record DeleteEventType(
    Long eventTypeId
) implements EventTypeOperation {
}
