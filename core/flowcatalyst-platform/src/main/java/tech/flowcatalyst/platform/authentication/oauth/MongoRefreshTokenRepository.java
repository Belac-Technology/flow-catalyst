package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of RefreshTokenRepository.
 * Package-private to prevent direct injection - use RefreshTokenRepository interface.
 */
@ApplicationScoped
@Typed(RefreshTokenRepository.class)
@Instrumented(collection = "refresh_tokens")
class MongoRefreshTokenRepository implements PanacheMongoRepositoryBase<RefreshToken, String>, RefreshTokenRepository {

    @Override
    public Optional<RefreshToken> findValidToken(String tokenHash) {
        return find("tokenHash = ?1 and revoked = false and expiresAt > ?2",
            tokenHash, Instant.now()).firstResultOptional();
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return find("tokenHash", tokenHash).firstResultOptional();
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
        List<RefreshToken> tokens = list("tokenFamily = ?1 and revoked = false", tokenFamily);
        Instant now = Instant.now();
        for (RefreshToken token : tokens) {
            token.revoked = true;
            token.revokedAt = now;
            update(token);
        }
    }

    @Override
    public void revokeAllForPrincipal(String principalId) {
        List<RefreshToken> tokens = list("principalId = ?1 and revoked = false", principalId);
        Instant now = Instant.now();
        for (RefreshToken token : tokens) {
            token.revoked = true;
            token.revokedAt = now;
            update(token);
        }
    }

    @Override
    public void revokeAllForPrincipalAndClient(String principalId, String clientId) {
        List<RefreshToken> tokens = list("principalId = ?1 and clientId = ?2 and revoked = false", principalId, clientId);
        Instant now = Instant.now();
        for (RefreshToken token : tokens) {
            token.revoked = true;
            token.revokedAt = now;
            update(token);
        }
    }

    @Override
    public List<RefreshToken> findActiveByPrincipalId(String principalId) {
        return find("principalId = ?1 and revoked = false and expiresAt > ?2",
            principalId, Instant.now()).list();
    }

    @Override
    public long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    @Override
    public long deleteRevokedOlderThan(Instant cutoff) {
        return delete("revoked = true and revokedAt < ?1", cutoff);
    }

    // Delegate to Panache methods via interface
    @Override
    public RefreshToken findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<RefreshToken> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<RefreshToken> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(RefreshToken token) {
        PanacheMongoRepositoryBase.super.persist(token);
    }

    @Override
    public void update(RefreshToken token) {
        PanacheMongoRepositoryBase.super.update(token);
    }

    @Override
    public void delete(RefreshToken token) {
        PanacheMongoRepositoryBase.super.delete(token);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
