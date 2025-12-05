package tech.flowcatalyst.platform.eventtype.operations;

/**
 * Operation to create a new EventType.
 * EventTypes are global (not tenant-scoped).
 *
 * @param code        Unique code in format {app}:{subdomain}:{aggregate}:{event}
 * @param name        Human-friendly name (max 100 chars)
 * @param description Optional description (max 255 chars)
 */
public record CreateEventType(
    String code,
    String name,
    String description
) implements EventTypeOperation {
}
