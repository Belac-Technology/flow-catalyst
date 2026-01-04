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
 * MongoDB implementation of AuthRoleRepository.
 * Package-private to prevent direct injection - use AuthRoleRepository interface.
 */
@ApplicationScoped
@Typed(AuthRoleRepository.class)
class MongoAuthRoleRepository implements AuthRoleRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<AuthRole> collection() {
        return mongoClient.getDatabase(database).getCollection("roles", AuthRole.class);
    }

    @Override
    public Optional<AuthRole> findByName(String name) {
        return Optional.ofNullable(collection().find(eq("name", name)).first());
    }

    @Override
    public List<AuthRole> findByApplicationCode(String applicationCode) {
        return collection().find(eq("applicationCode", applicationCode)).into(new ArrayList<>());
    }

    @Override
    public List<AuthRole> findBySource(AuthRole.RoleSource source) {
        return collection().find(eq("source", source.name())).into(new ArrayList<>());
    }

    @Override
    public List<AuthRole> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public boolean existsByName(String name) {
        return collection().countDocuments(eq("name", name)) > 0;
    }

    @Override
    public void persist(AuthRole role) {
        collection().insertOne(role);
    }

    @Override
    public void update(AuthRole role) {
        collection().replaceOne(eq("_id", role.id), role);
    }

    @Override
    public void delete(AuthRole role) {
        collection().deleteOne(eq("_id", role.id));
    }
}
