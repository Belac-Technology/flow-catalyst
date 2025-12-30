package tech.flowcatalyst.schema;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.eventtype.SchemaType;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of SchemaRepository.
 * Package-private to prevent direct injection - use SchemaRepository interface.
 * Schemas can be standalone or linked to EventTypes.
 */
@ApplicationScoped
@Typed(SchemaRepository.class)
@Instrumented(collection = "schemas")
class MongoSchemaRepository implements PanacheMongoRepositoryBase<Schema, String>, SchemaRepository {

    @Override
    public Optional<Schema> findByEventTypeAndVersion(String eventTypeId, String version) {
        return find("eventTypeId = ?1 and version = ?2", eventTypeId, version).firstResultOptional();
    }

    @Override
    public List<Schema> findByEventType(String eventTypeId) {
        return list("eventTypeId", Sort.by("version"), eventTypeId);
    }

    @Override
    public List<Schema> findStandalone() {
        return list("eventTypeId is null");
    }

    @Override
    public List<Schema> findBySchemaType(SchemaType schemaType) {
        return list("schemaType", schemaType);
    }

    @Override
    public boolean existsByEventTypeAndVersion(String eventTypeId, String version) {
        return count("eventTypeId = ?1 and version = ?2", eventTypeId, version) > 0;
    }

    // Delegate to Panache methods via interface
    @Override
    public Schema findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<Schema> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<Schema> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(Schema schema) {
        PanacheMongoRepositoryBase.super.persist(schema);
    }

    @Override
    public void update(Schema schema) {
        PanacheMongoRepositoryBase.super.update(schema);
    }

    @Override
    public void delete(Schema schema) {
        PanacheMongoRepositoryBase.super.delete(schema);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
