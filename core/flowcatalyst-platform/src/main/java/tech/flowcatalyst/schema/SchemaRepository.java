package tech.flowcatalyst.schema;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.eventtype.SchemaType;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Schema entities.
 * Schemas can be standalone or linked to EventTypes.
 */
@ApplicationScoped
public class SchemaRepository implements PanacheMongoRepositoryBase<Schema, String> {

    /**
     * Find a schema by event type ID and version.
     */
    public Optional<Schema> findByEventTypeAndVersion(String eventTypeId, String version) {
        return find("eventTypeId = ?1 and version = ?2", eventTypeId, version).firstResultOptional();
    }

    /**
     * Find all schemas for an event type.
     */
    public List<Schema> findByEventType(String eventTypeId) {
        return list("eventTypeId", Sort.by("version"), eventTypeId);
    }

    /**
     * Find all standalone schemas (not linked to an EventType).
     */
    public List<Schema> findStandalone() {
        return list("eventTypeId is null");
    }

    /**
     * Find schemas by schema type.
     */
    public List<Schema> findBySchemaType(SchemaType schemaType) {
        return list("schemaType", schemaType);
    }

    /**
     * Check if a schema exists for the given event type and version.
     */
    public boolean existsByEventTypeAndVersion(String eventTypeId, String version) {
        return count("eventTypeId = ?1 and version = ?2", eventTypeId, version) > 0;
    }
}
