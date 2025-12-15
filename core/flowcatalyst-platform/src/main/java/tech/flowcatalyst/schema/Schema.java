package tech.flowcatalyst.schema;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;
import tech.flowcatalyst.eventtype.SchemaType;

import java.time.Instant;

/**
 * A reusable schema definition.
 *
 * Schemas define the structure of event payloads and can be:
 * - Referenced by EventType SpecVersions (eventTypeId + version set)
 * - Standalone for direct use with DispatchJobs (eventTypeId is null)
 *
 * Content is stored as a string (JSON Schema, Proto, or XSD).
 */
@MongoEntity(collection = "schemas")
public record Schema(
    @BsonId
    String id,

    /** Human-friendly name (optional) */
    String name,

    /** Description of what this schema validates (optional) */
    String description,

    /** MIME type for payloads using this schema (e.g., "application/json") */
    String mimeType,

    /** Type of schema (JSON_SCHEMA, PROTO, XSD) */
    SchemaType schemaType,

    /** The schema definition content */
    String content,

    /** Associated event type ID (null for standalone schemas) */
    String eventTypeId,

    /** Version string when linked to an EventType (null for standalone) */
    String version,

    Instant createdAt,
    Instant updatedAt
) {
    /**
     * Check if this is a standalone schema (not linked to an EventType).
     */
    public boolean isStandalone() {
        return eventTypeId == null;
    }

    /**
     * Check if this schema is linked to an EventType.
     */
    public boolean isLinkedToEventType() {
        return eventTypeId != null;
    }
}
