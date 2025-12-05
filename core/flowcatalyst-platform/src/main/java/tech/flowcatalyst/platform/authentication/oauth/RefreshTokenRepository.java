package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class RefreshTokenRepository implements PanacheRepositoryBase<RefreshToken, String> {

    /**
     * Find a valid (not revoked and not expired) refresh token by its hash.
     */
    public Optional<RefreshToken> findValidToken(String tokenHash) {
        return find("tokenHash = ?1 AND revoked = false AND expiresAt > ?2",
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
    @Transactional
    public void revokeToken(String tokenHash, String replacedBy) {
        update("revoked = true, revokedAt = ?1, replacedBy = ?2 WHERE tokenHash = ?3",
            Instant.now(), replacedBy, tokenHash);
    }

    /**
     * Revoke all tokens in a family (on reuse detection - potential theft).
     */
    @Transactional
    public void revokeTokenFamily(String tokenFamily) {
        update("revoked = true, revokedAt = ?1 WHERE tokenFamily = ?2 AND revoked = false",
            Instant.now(), tokenFamily);
    }

    /**
     * Revoke all tokens for a principal (e.g., on password change, logout all).
     */
    @Transactional
    public void revokeAllForPrincipal(Long principalId) {
        update("revoked = true, revokedAt = ?1 WHERE principalId = ?2 AND revoked = false",
            Instant.now(), principalId);
    }

    /**
     * Revoke all tokens for a principal and client (e.g., on client deauthorization).
     */
    @Transactional
    public void revokeAllForPrincipalAndClient(Long principalId, String clientId) {
        update("revoked = true, revokedAt = ?1 WHERE principalId = ?2 AND clientId = ?3 AND revoked = false",
            Instant.now(), principalId, clientId);
    }

    /**
     * Find all active tokens for a principal (for token management UI).
     */
    public List<RefreshToken> findActiveByPrincipalId(Long principalId) {
        return find("principalId = ?1 AND revoked = false AND expiresAt > ?2",
            principalId, Instant.now()).list();
    }

    /**
     * Delete all expired tokens (cleanup job).
     */
    @Transactional
    public long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    /**
     * Delete all revoked tokens older than given time (cleanup job).
     */
    @Transactional
    public long deleteRevokedOlderThan(Instant cutoff) {
        return delete("revoked = true AND revokedAt < ?1", cutoff);
    }
}
