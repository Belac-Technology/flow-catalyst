package tech.flowcatalyst.eventtype.operations;

/**
 * Operation to finalise a schema version (FINALISING → CURRENT).
 *
 * @param eventTypeId The ID of the event type
 * @param version     The version to finalise
 */
public record FinaliseSchema(
    Long eventTypeId,
    String version
) implements EventTypeOperation {
}
