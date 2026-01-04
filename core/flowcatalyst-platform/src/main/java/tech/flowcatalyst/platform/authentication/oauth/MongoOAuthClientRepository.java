package tech.flowcatalyst.platform.authentication.oauth;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.DeleteResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of OAuthClientRepository.
 * Package-private to prevent direct injection - use OAuthClientRepository interface.
 */
@ApplicationScoped
@Typed(OAuthClientRepository.class)
class MongoOAuthClientRepository implements OAuthClientRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<OAuthClient> collection() {
        return mongoClient.getDatabase(database).getCollection("oauth_clients", OAuthClient.class);
    }

    @Override
    public Optional<OAuthClient> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<OAuthClient> findByClientId(String clientId) {
        return Optional.ofNullable(collection().find(and(eq("clientId", clientId), eq("active", true))).first());
    }

    @Override
    public Optional<OAuthClient> findByClientIdIncludingInactive(String clientId) {
        return Optional.ofNullable(collection().find(eq("clientId", clientId)).first());
    }

    @Override
    public List<OAuthClient> findByApplicationIdAndActive(String applicationId, boolean active) {
        return collection().find(and(in("applicationIds", applicationId), eq("active", active)))
            .into(new ArrayList<>());
    }

    @Override
    public List<OAuthClient> findByApplicationId(String applicationId) {
        return collection().find(in("applicationIds", applicationId))
            .into(new ArrayList<>());
    }

    @Override
    public List<OAuthClient> findByActive(boolean active) {
        return collection().find(eq("active", active))
            .into(new ArrayList<>());
    }

    @Override
    public List<OAuthClient> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public void persist(OAuthClient client) {
        collection().insertOne(client);
    }

    @Override
    public void update(OAuthClient client) {
        collection().replaceOne(eq("_id", client.id), client);
    }

    @Override
    public void delete(OAuthClient client) {
        collection().deleteOne(eq("_id", client.id));
    }

    @Override
    public long deleteByServiceAccountPrincipalId(String principalId) {
        DeleteResult result = collection().deleteMany(eq("serviceAccountPrincipalId", principalId));
        return result.getDeletedCount();
    }
}
