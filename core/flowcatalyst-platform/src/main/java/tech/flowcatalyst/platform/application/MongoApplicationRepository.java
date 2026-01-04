package tech.flowcatalyst.platform.application;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of ApplicationRepository.
 * Package-private to prevent direct injection - use ApplicationRepository interface.
 */
@ApplicationScoped
@Typed(ApplicationRepository.class)
class MongoApplicationRepository implements ApplicationRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<Application> collection() {
        return mongoClient.getDatabase(database).getCollection("applications", Application.class);
    }

    @Override
    public Optional<Application> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<Application> findByCode(String code) {
        return Optional.ofNullable(collection().find(eq("code", code)).first());
    }

    @Override
    public List<Application> findAllActive() {
        return collection().find(eq("active", true)).into(new ArrayList<>());
    }

    @Override
    public List<Application> findByType(Application.ApplicationType type, boolean activeOnly) {
        if (activeOnly) {
            return collection().find(and(
                eq("type", type.name()),
                eq("active", true)
            )).into(new ArrayList<>());
        }
        return collection().find(eq("type", type.name())).into(new ArrayList<>());
    }

    @Override
    public List<Application> findByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return collection().find(and(
            in("code", codes),
            eq("active", true)
        )).into(new ArrayList<>());
    }

    @Override
    public List<Application> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return collection().find(in("_id", ids)).into(new ArrayList<>());
    }

    @Override
    public List<Application> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public boolean existsByCode(String code) {
        return collection().countDocuments(eq("code", code)) > 0;
    }

    @Override
    public void persist(Application application) {
        collection().insertOne(application);
    }

    @Override
    public void update(Application application) {
        collection().replaceOne(eq("_id", application.id), application);
    }

    @Override
    public void delete(Application application) {
        collection().deleteOne(eq("_id", application.id));
    }
}
