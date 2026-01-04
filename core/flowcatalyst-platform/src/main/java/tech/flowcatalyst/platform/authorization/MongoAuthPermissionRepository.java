package tech.flowcatalyst.platform.authorization;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of AuthPermissionRepository.
 * Package-private to prevent direct injection - use AuthPermissionRepository interface.
 */
@ApplicationScoped
@Typed(AuthPermissionRepository.class)
class MongoAuthPermissionRepository implements AuthPermissionRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<AuthPermission> collection() {
        return mongoClient.getDatabase(database).getCollection("permissions", AuthPermission.class);
    }

    @Override
    public Optional<AuthPermission> findByName(String name) {
        return Optional.ofNullable(collection().find(eq("name", name)).first());
    }

    @Override
    public List<AuthPermission> findByApplicationId(String applicationId) {
        return collection().find(eq("applicationId", applicationId)).into(new ArrayList<>());
    }

    @Override
    public List<AuthPermission> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public boolean existsByName(String name) {
        return collection().countDocuments(eq("name", name)) > 0;
    }

    @Override
    public void persist(AuthPermission permission) {
        collection().insertOne(permission);
    }

    @Override
    public void update(AuthPermission permission) {
        collection().replaceOne(eq("_id", permission.id), permission);
    }

    @Override
    public void delete(AuthPermission permission) {
        collection().deleteOne(eq("_id", permission.id));
    }

    @Override
    public long deleteByApplicationId(String applicationId) {
        return collection().deleteMany(eq("applicationId", applicationId)).getDeletedCount();
    }
}
