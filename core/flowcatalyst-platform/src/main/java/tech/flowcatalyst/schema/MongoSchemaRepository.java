package tech.flowcatalyst.schema;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.flowcatalyst.eventtype.SchemaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of SchemaRepository.
 * Package-private to prevent direct injection - use SchemaRepository interface.
 * Schemas can be standalone or linked to EventTypes.
 */
@ApplicationScoped
@Typed(SchemaRepository.class)
class MongoSchemaRepository implements SchemaRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<Schema> collection() {
        return mongoClient.getDatabase(database).getCollection("schemas", Schema.class);
    }

    @Override
    public Schema findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<Schema> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<Schema> findByEventTypeAndVersion(String eventTypeId, String version) {
        return Optional.ofNullable(
            collection().find(and(eq("eventTypeId", eventTypeId), eq("version", version))).first()
        );
    }

    @Override
    public List<Schema> findByEventType(String eventTypeId) {
        return collection().find(eq("eventTypeId", eventTypeId))
            .sort(Sorts.ascending("version"))
            .into(new ArrayList<>());
    }

    @Override
    public List<Schema> findStandalone() {
        return collection().find(eq("eventTypeId", null)).into(new ArrayList<>());
    }

    @Override
    public List<Schema> findBySchemaType(SchemaType schemaType) {
        return collection().find(eq("schemaType", schemaType)).into(new ArrayList<>());
    }

    @Override
    public boolean existsByEventTypeAndVersion(String eventTypeId, String version) {
        return collection().countDocuments(and(eq("eventTypeId", eventTypeId), eq("version", version))) > 0;
    }

    @Override
    public List<Schema> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public void persist(Schema schema) {
        collection().insertOne(schema);
    }

    @Override
    public void update(Schema schema) {
        collection().replaceOne(eq("_id", schema.id()), schema);
    }

    @Override
    public void delete(Schema schema) {
        collection().deleteOne(eq("_id", schema.id()));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
