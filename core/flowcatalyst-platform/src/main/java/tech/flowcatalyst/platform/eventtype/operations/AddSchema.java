package tech.flowcatalyst.platform.eventtype.operations;

import tech.flowcatalyst.platform.eventtype.SchemaType;

/**
 * Operation to add a new schema version to an EventType.
 *
 * New schemas start in FINALISING status.
 *
 * @param eventTypeId The EventType to add the schema to
 * @param version     Version string in format "MAJOR.MINOR" (e.g., "1.0", "2.0")
 * @param mimeType    MIME type (e.g., "application/json")
 * @param schema      The schema definition content
 * @param schemaType  Type of schema (JSON_SCHEMA, PROTO, XSD)
 */
public record AddSchema(
    Long eventTypeId,
    String version,
    String mimeType,
    String schema,
    SchemaType schemaType
) implements EventTypeOperation {
}
