package tech.flowcatalyst.eventtype.operations;

/**
 * Operation to delete an EventType.
 *
 * @param eventTypeId The ID of the event type to delete
 */
public record DeleteEventType(
    Long eventTypeId
) implements EventTypeOperation {
}
