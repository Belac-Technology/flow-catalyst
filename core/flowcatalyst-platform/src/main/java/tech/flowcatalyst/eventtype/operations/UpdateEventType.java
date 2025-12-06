package tech.flowcatalyst.eventtype.operations;

/**
 * Operation to update an EventType's name or description.
 *
 * @param eventTypeId The ID of the event type to update
 * @param name        New name (optional, null means no change)
 * @param description New description (optional, null means no change)
 */
public record UpdateEventType(
    Long eventTypeId,
    String name,
    String description
) implements EventTypeOperation {
}
