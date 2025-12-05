package tech.flowcatalyst.platform.eventtype.operations;

/**
 * Operation to finalise a schema version.
 *
 * Changes status from FINALISING → CURRENT.
 * If another schema with the same major version is CURRENT, it becomes DEPRECATED.
 *
 * @param eventTypeId The EventType containing the schema
 * @param version     The version to finalise (e.g., "1.0")
 */
public record FinaliseSchema(
    Long eventTypeId,
    String version
) implements EventTypeOperation {
}
