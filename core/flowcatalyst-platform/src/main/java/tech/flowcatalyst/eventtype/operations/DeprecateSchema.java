package tech.flowcatalyst.eventtype.operations;

/**
 * Operation to deprecate a schema version (CURRENT → DEPRECATED).
 *
 * @param eventTypeId The ID of the event type
 * @param version     The version to deprecate
 */
public record DeprecateSchema(
    Long eventTypeId,
    String version
) implements EventTypeOperation {
}
