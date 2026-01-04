package tech.flowcatalyst.dispatchpool;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of DispatchPoolRepository.
 * Package-private to prevent direct injection - use DispatchPoolRepository interface.
 */
@ApplicationScoped
@Typed(DispatchPoolRepository.class)
class MongoDispatchPoolRepository implements DispatchPoolRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<DispatchPool> collection() {
        return mongoClient.getDatabase(database).getCollection("dispatch_pools", DispatchPool.class);
    }

    @Override
    public DispatchPool findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<DispatchPool> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<DispatchPool> findByCodeAndClientId(String code, String clientId) {
        Bson filter = clientId == null
            ? and(eq("code", code), eq("clientId", null))
            : and(eq("code", code), eq("clientId", clientId));
        return Optional.ofNullable(collection().find(filter).first());
    }

    @Override
    public boolean existsByCodeAndClientId(String code, String clientId) {
        Bson filter = clientId == null
            ? and(eq("code", code), eq("clientId", null))
            : and(eq("code", code), eq("clientId", clientId));
        return collection().countDocuments(filter) > 0;
    }

    @Override
    public List<DispatchPool> findByClientId(String clientId) {
        return collection().find(eq("clientId", clientId))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<DispatchPool> findAnchorLevel() {
        return collection().find(eq("clientId", null))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<DispatchPool> findByStatus(DispatchPoolStatus status) {
        return collection().find(eq("status", status))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<DispatchPool> findActive() {
        return findByStatus(DispatchPoolStatus.ACTIVE);
    }

    @Override
    public List<DispatchPool> findAllNonArchived() {
        return collection().find(ne("status", DispatchPoolStatus.ARCHIVED))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<DispatchPool> findWithFilters(String clientId, DispatchPoolStatus status, boolean includeArchived) {
        List<Bson> conditions = new ArrayList<>();

        if (clientId != null) {
            conditions.add(eq("clientId", clientId));
        }

        if (status != null) {
            conditions.add(eq("status", status));
        } else if (!includeArchived) {
            conditions.add(ne("status", DispatchPoolStatus.ARCHIVED));
        }

        Bson filter = conditions.isEmpty() ? new org.bson.Document() : and(conditions);

        return collection().find(filter)
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<DispatchPool> listAll() {
        return collection().find()
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public void persist(DispatchPool pool) {
        collection().insertOne(pool);
    }

    @Override
    public void update(DispatchPool pool) {
        collection().replaceOne(eq("_id", pool.id()), pool);
    }

    @Override
    public void delete(DispatchPool pool) {
        collection().deleteOne(eq("_id", pool.id()));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
