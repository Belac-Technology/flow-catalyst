package tech.flowcatalyst.platform.eventtype.operations;

/**
 * Operation to deprecate a schema version.
 *
 * Changes status from CURRENT → DEPRECATED.
 * Cannot deprecate FINALISING schemas (must finalise or delete them).
 *
 * @param eventTypeId The EventType containing the schema
 * @param version     The version to deprecate (e.g., "1.0")
 */
public record DeprecateSchema(
    Long eventTypeId,
    String version
) implements EventTypeOperation {
}
