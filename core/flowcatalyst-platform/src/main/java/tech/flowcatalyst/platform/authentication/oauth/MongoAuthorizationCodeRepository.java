package tech.flowcatalyst.platform.authentication.oauth;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of AuthorizationCodeRepository.
 * Package-private to prevent direct injection - use AuthorizationCodeRepository interface.
 */
@ApplicationScoped
@Typed(AuthorizationCodeRepository.class)
class MongoAuthorizationCodeRepository implements AuthorizationCodeRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<AuthorizationCode> collection() {
        return mongoClient.getDatabase(database).getCollection("authorization_codes", AuthorizationCode.class);
    }

    @Override
    public Optional<AuthorizationCode> findValidCode(String code) {
        return Optional.ofNullable(collection().find(and(
            eq("code", code),
            eq("used", false),
            gt("expiresAt", Instant.now())
        )).first());
    }

    @Override
    public void persist(AuthorizationCode code) {
        collection().insertOne(code);
    }

    @Override
    public void markAsUsed(String code) {
        collection().updateOne(
            eq("code", code),
            new Document("$set", new Document("used", true))
        );
    }
}
