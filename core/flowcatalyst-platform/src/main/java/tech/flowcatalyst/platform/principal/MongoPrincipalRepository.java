package tech.flowcatalyst.platform.principal;

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
 * MongoDB implementation of PrincipalRepository.
 * Package-private to prevent direct injection - use PrincipalRepository interface.
 */
@ApplicationScoped
@Typed(PrincipalRepository.class)
class MongoPrincipalRepository implements PrincipalRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<Principal> collection() {
        return mongoClient.getDatabase(database).getCollection("principals", Principal.class);
    }

    @Override
    public Principal findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<Principal> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<Principal> findByEmail(String email) {
        return Optional.ofNullable(collection().find(eq("userIdentity.email", email)).first());
    }

    @Override
    public Optional<Principal> findByServiceAccountCode(String code) {
        return Optional.ofNullable(collection().find(eq("serviceAccount.code", code)).first());
    }

    @Override
    public List<Principal> findByType(PrincipalType type) {
        return collection().find(eq("type", type.name())).into(new ArrayList<>());
    }

    @Override
    public List<Principal> findByClientId(String clientId) {
        return collection().find(eq("clientId", clientId)).into(new ArrayList<>());
    }

    @Override
    public List<Principal> findByIds(Collection<String> ids) {
        return collection().find(in("_id", ids)).into(new ArrayList<>());
    }

    @Override
    public List<Principal> findUsersByClientId(String clientId) {
        return collection().find(and(
            eq("clientId", clientId),
            eq("type", PrincipalType.USER.name())
        )).into(new ArrayList<>());
    }

    @Override
    public List<Principal> findActiveUsersByClientId(String clientId) {
        return collection().find(and(
            eq("clientId", clientId),
            eq("type", PrincipalType.USER.name()),
            eq("active", true)
        )).into(new ArrayList<>());
    }

    @Override
    public List<Principal> findByClientIdAndTypeAndActive(String clientId, PrincipalType type, Boolean active) {
        return collection().find(and(
            eq("clientId", clientId),
            eq("type", type.name()),
            eq("active", active)
        )).into(new ArrayList<>());
    }

    @Override
    public List<Principal> findByClientIdAndType(String clientId, PrincipalType type) {
        return collection().find(and(
            eq("clientId", clientId),
            eq("type", type.name())
        )).into(new ArrayList<>());
    }

    @Override
    public List<Principal> findByClientIdAndActive(String clientId, Boolean active) {
        return collection().find(and(
            eq("clientId", clientId),
            eq("active", active)
        )).into(new ArrayList<>());
    }

    @Override
    public List<Principal> findByActive(Boolean active) {
        return collection().find(eq("active", active)).into(new ArrayList<>());
    }

    @Override
    public List<Principal> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public Optional<Principal> findByServiceAccountClientId(String clientId) {
        return Optional.ofNullable(collection().find(and(
            eq("type", PrincipalType.SERVICE.name()),
            eq("serviceAccount.clientId", clientId)
        )).first());
    }

    @Override
    public long countByEmailDomain(String domain) {
        return collection().countDocuments(regex("userIdentity.email", ".*@" + domain + "$", "i"));
    }

    @Override
    public void persist(Principal principal) {
        collection().insertOne(principal);
    }

    @Override
    public void update(Principal principal) {
        collection().replaceOne(eq("_id", principal.id), principal);
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
