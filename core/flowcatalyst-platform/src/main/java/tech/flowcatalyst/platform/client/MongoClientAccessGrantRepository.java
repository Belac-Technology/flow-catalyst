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
 * MongoDB implementation of ClientAccessGrantRepository.
 * Package-private to prevent direct injection - use ClientAccessGrantRepository interface.
 */
@ApplicationScoped
@Typed(ClientAccessGrantRepository.class)
class MongoClientAccessGrantRepository implements ClientAccessGrantRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<ClientAccessGrant> collection() {
        return mongoClient.getDatabase(database).getCollection("client_access_grants", ClientAccessGrant.class);
    }

    @Override
    public List<ClientAccessGrant> findByPrincipalId(String principalId) {
        return collection().find(eq("principalId", principalId)).into(new ArrayList<>());
    }

    @Override
    public List<ClientAccessGrant> findByClientId(String clientId) {
        return collection().find(eq("clientId", clientId)).into(new ArrayList<>());
    }

    @Override
    public Optional<ClientAccessGrant> findByPrincipalIdAndClientId(String principalId, String clientId) {
        return Optional.ofNullable(collection().find(and(
            eq("principalId", principalId),
            eq("clientId", clientId)
        )).first());
    }

    @Override
    public boolean existsByPrincipalIdAndClientId(String principalId, String clientId) {
        return collection().countDocuments(and(
            eq("principalId", principalId),
            eq("clientId", clientId)
        )) > 0;
    }

    @Override
    public void persist(ClientAccessGrant grant) {
        collection().insertOne(grant);
    }

    @Override
    public void delete(ClientAccessGrant grant) {
        collection().deleteOne(eq("_id", grant.id));
    }

    @Override
    public void deleteByPrincipalId(String principalId) {
        collection().deleteMany(eq("principalId", principalId));
    }

    @Override
    public long deleteByPrincipalIdAndClientId(String principalId, String clientId) {
        return collection().deleteMany(and(
            eq("principalId", principalId),
            eq("clientId", clientId)
        )).getDeletedCount();
    }
}
