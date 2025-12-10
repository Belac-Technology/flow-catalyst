package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RefreshTokenRepository implements PanacheMongoRepositoryBase<RefreshToken, String> {

    /**
     * Find a valid (not revoked and not expired) refresh token by its hash.
     */
    public Optional<RefreshToken> findValidToken(String tokenHash) {
        return find("tokenHash = ?1 and revoked = false and expiresAt > ?2",
            tokenHash, Instant.now()).firstResultOptional();
    }

    /**
     * Find a token by hash (including revoked/expired) for reuse detection.
     */
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return find("tokenHash", tokenHash).firstResultOptional();
    }

    /**
     * Revoke a token and record what replaced it (for rotation tracking).
     */
    public void revokeToken(String tokenHash, String replacedBy) {
        Optional<RefreshToken> tokenOpt = findByTokenHash(tokenHash);
        tokenOpt.ifPresent(token -> {
            token.revoked = true;
            token.revokedAt = Instant.now();
            token.replacedBy = replacedBy;
            update(token);
        });
    }

    /**
     * Revoke all tokens in a family (on reuse detection - potential theft).
     */
    public void revokeTokenFamily(String tokenFamily) {
        List<RefreshToken> tokens = list("tokenFamily = ?1 and revoked = false", tokenFamily);
        Instant now = Instant.now();
        for (RefreshToken token : tokens) {
            token.revoked = true;
            token.revokedAt = now;
            update(token);
        }
    }

    /**
     * Revoke all tokens for a principal (e.g., on password change, logout all).
     */
    public void revokeAllForPrincipal(String principalId) {
        List<RefreshToken> tokens = list("principalId = ?1 and revoked = false", principalId);
        Instant now = Instant.now();
        for (RefreshToken token : tokens) {
            token.revoked = true;
            token.revokedAt = now;
            update(token);
        }
    }

    /**
     * Revoke all tokens for a principal and client (e.g., on client deauthorization).
     */
    public void revokeAllForPrincipalAndClient(String principalId, String clientId) {
        List<RefreshToken> tokens = list("principalId = ?1 and clientId = ?2 and revoked = false", principalId, clientId);
        Instant now = Instant.now();
        for (RefreshToken token : tokens) {
            token.revoked = true;
            token.revokedAt = now;
            update(token);
        }
    }

    /**
     * Find all active tokens for a principal (for token management UI).
     */
    public List<RefreshToken> findActiveByPrincipalId(String principalId) {
        return find("principalId = ?1 and revoked = false and expiresAt > ?2",
            principalId, Instant.now()).list();
    }

    /**
     * Delete all expired tokens (cleanup job).
     */
    public long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    /**
     * Delete all revoked tokens older than given time (cleanup job).
     */
    public long deleteRevokedOlderThan(Instant cutoff) {
        return delete("revoked = true and revokedAt < ?1", cutoff);
    }
}
