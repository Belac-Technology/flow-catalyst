package tech.flowcatalyst.platform.authentication.oidc;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of OidcLoginStateRepository.
 * Package-private to prevent direct injection - use OidcLoginStateRepository interface.
 */
@ApplicationScoped
@Typed(OidcLoginStateRepository.class)
class MongoOidcLoginStateRepository implements OidcLoginStateRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<OidcLoginState> collection() {
        return mongoClient.getDatabase(database).getCollection("oidc_login_states", OidcLoginState.class);
    }

    @Override
    public Optional<OidcLoginState> findValidState(String state) {
        return Optional.ofNullable(collection().find(and(
            eq("_id", state),
            gt("expiresAt", Instant.now())
        )).first());
    }

    @Override
    public void persist(OidcLoginState state) {
        collection().insertOne(state);
    }

    @Override
    public void deleteByState(String state) {
        collection().deleteOne(eq("_id", state));
    }
}
