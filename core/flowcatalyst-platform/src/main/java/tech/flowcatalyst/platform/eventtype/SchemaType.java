package tech.flowcatalyst.platform.eventtype;

/**
 * Type of schema definition.
 */
public enum SchemaType {
    /**
     * JSON Schema (draft-07 or later).
     */
    JSON_SCHEMA,

    /**
     * Protocol Buffers schema.
     */
    PROTO,

    /**
     * XML Schema Definition.
     */
    XSD
}
