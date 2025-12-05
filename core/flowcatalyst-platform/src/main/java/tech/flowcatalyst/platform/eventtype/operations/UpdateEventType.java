package tech.flowcatalyst.platform.eventtype.operations;

/**
 * Operation to update an EventType's name or description.
 *
 * Note: Code cannot be changed - create a new EventType if needed.
 *
 * @param eventTypeId The EventType to update
 * @param name        New name (null to keep existing)
 * @param description New description (null to keep existing)
 */
public record UpdateEventType(
    Long eventTypeId,
    String name,
    String description
) implements EventTypeOperation {
}
