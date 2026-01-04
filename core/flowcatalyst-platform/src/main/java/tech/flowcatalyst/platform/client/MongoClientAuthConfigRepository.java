package tech.flowcatalyst.platform.client;

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
 * MongoDB implementation of ClientAuthConfigRepository.
 * Package-private to prevent direct injection - use ClientAuthConfigRepository interface.
 */
@ApplicationScoped
@Typed(ClientAuthConfigRepository.class)
class MongoClientAuthConfigRepository implements ClientAuthConfigRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<ClientAuthConfig> collection() {
        return mongoClient.getDatabase(database).getCollection("auth_configs", ClientAuthConfig.class);
    }

    @Override
    public Optional<ClientAuthConfig> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<ClientAuthConfig> findByEmailDomain(String emailDomain) {
        return Optional.ofNullable(collection().find(eq("emailDomain", emailDomain)).first());
    }

    @Override
    public List<ClientAuthConfig> findByClientId(String clientId) {
        return collection().find(or(
            eq("primaryClientId", clientId),
            eq("clientId", clientId)
        )).into(new ArrayList<>());
    }

    @Override
    public List<ClientAuthConfig> findByConfigType(AuthConfigType configType) {
        return collection().find(eq("configType", configType.name())).into(new ArrayList<>());
    }

    @Override
    public List<ClientAuthConfig> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public boolean existsByEmailDomain(String emailDomain) {
        return collection().countDocuments(eq("emailDomain", emailDomain)) > 0;
    }

    @Override
    public void persist(ClientAuthConfig config) {
        collection().insertOne(config);
    }

    @Override
    public void update(ClientAuthConfig config) {
        collection().replaceOne(eq("_id", config.id), config);
    }

    @Override
    public void delete(ClientAuthConfig config) {
        collection().deleteOne(eq("_id", config.id));
    }
}
