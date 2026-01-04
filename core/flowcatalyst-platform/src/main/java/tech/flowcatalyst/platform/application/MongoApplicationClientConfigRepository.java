package tech.flowcatalyst.platform.application;

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
 * MongoDB implementation of ApplicationClientConfigRepository.
 * Package-private to prevent direct injection - use ApplicationClientConfigRepository interface.
 */
@ApplicationScoped
@Typed(ApplicationClientConfigRepository.class)
class MongoApplicationClientConfigRepository implements ApplicationClientConfigRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<ApplicationClientConfig> collection() {
        return mongoClient.getDatabase(database).getCollection("application_client_config", ApplicationClientConfig.class);
    }

    @Override
    public Optional<ApplicationClientConfig> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<ApplicationClientConfig> findByApplicationAndClient(String applicationId, String clientId) {
        return Optional.ofNullable(collection().find(and(
            eq("applicationId", applicationId),
            eq("clientId", clientId)
        )).first());
    }

    @Override
    public List<ApplicationClientConfig> findByApplication(String applicationId) {
        return collection().find(eq("applicationId", applicationId)).into(new ArrayList<>());
    }

    @Override
    public List<ApplicationClientConfig> findByClient(String clientId) {
        return collection().find(eq("clientId", clientId)).into(new ArrayList<>());
    }

    @Override
    public List<ApplicationClientConfig> findEnabledByClient(String clientId) {
        return collection().find(and(
            eq("clientId", clientId),
            eq("enabled", true)
        )).into(new ArrayList<>());
    }

    @Override
    public boolean isApplicationEnabledForClient(String applicationId, String clientId) {
        return collection().countDocuments(and(
            eq("applicationId", applicationId),
            eq("clientId", clientId),
            eq("enabled", true)
        )) > 0;
    }

    @Override
    public long countByApplication(String applicationId) {
        return collection().countDocuments(eq("applicationId", applicationId));
    }

    @Override
    public void persist(ApplicationClientConfig config) {
        collection().insertOne(config);
    }

    @Override
    public void update(ApplicationClientConfig config) {
        collection().replaceOne(eq("_id", config.id), config);
    }

    @Override
    public void delete(ApplicationClientConfig config) {
        collection().deleteOne(eq("_id", config.id));
    }

    @Override
    public void deleteByApplicationAndClient(String applicationId, String clientId) {
        collection().deleteMany(and(
            eq("applicationId", applicationId),
            eq("clientId", clientId)
        ));
    }
}
