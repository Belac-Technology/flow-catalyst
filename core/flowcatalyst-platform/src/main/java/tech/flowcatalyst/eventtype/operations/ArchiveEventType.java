package tech.flowcatalyst.eventtype.operations;

/**
 * Operation to archive an EventType (CURRENT → ARCHIVE).
 *
 * @param eventTypeId The ID of the event type to archive
 */
public record ArchiveEventType(
    Long eventTypeId
) implements EventTypeOperation {
}
