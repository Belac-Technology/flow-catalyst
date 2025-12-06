package tech.flowcatalyst.eventtype.operations;

import tech.flowcatalyst.eventtype.SchemaType;

/**
 * Operation to add a new schema version to an EventType.
 *
 * @param eventTypeId The ID of the event type
 * @param version     Version string in format "MAJOR.MINOR"
 * @param mimeType    MIME type for the event payload
 * @param schema      The schema definition content
 * @param schemaType  The type of schema (JSON_SCHEMA, PROTO, XSD)
 */
public record AddSchema(
    Long eventTypeId,
    String version,
    String mimeType,
    String schema,
    SchemaType schemaType
) implements EventTypeOperation {
}
