package tech.flowcatalyst.platform.authentication.oauth;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of RefreshTokenRepository.
 * Package-private to prevent direct injection - use RefreshTokenRepository interface.
 */
@ApplicationScoped
@Typed(RefreshTokenRepository.class)
class MongoRefreshTokenRepository implements RefreshTokenRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<RefreshToken> collection() {
        return mongoClient.getDatabase(database).getCollection("refresh_tokens", RefreshToken.class);
    }

    @Override
    public Optional<RefreshToken> findValidToken(String tokenHash) {
        return Optional.ofNullable(collection().find(and(
            eq("tokenHash", tokenHash),
            eq("revoked", false),
            gt("expiresAt", Instant.now())
        )).first());
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(collection().find(eq("tokenHash", tokenHash)).first());
    }

    @Override
    public void persist(RefreshToken token) {
        collection().insertOne(token);
    }

    @Override
    public void update(RefreshToken token) {
        collection().replaceOne(eq("tokenHash", token.tokenHash), token);
    }

    @Override
    public void revokeToken(String tokenHash, String replacedBy) {
        Optional<RefreshToken> tokenOpt = findByTokenHash(tokenHash);
        tokenOpt.ifPresent(token -> {
            token.revoked = true;
            token.revokedAt = Instant.now();
            token.replacedBy = replacedBy;
            update(token);
        });
    }

    @Override
    public void revokeTokenFamily(String tokenFamily) {
        List<RefreshToken> tokens = collection().find(and(
            eq("tokenFamily", tokenFamily),
            eq("revoked", false)
        )).into(new ArrayList<>());

        Instant now = Instant.now();
        for (RefreshToken token : tokens) {
            token.revoked = true;
            token.revokedAt = now;
            update(token);
        }
    }
}
