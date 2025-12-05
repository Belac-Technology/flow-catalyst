package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class AuthorizationCodeRepository implements PanacheRepositoryBase<AuthorizationCode, String> {

    /**
     * Find a valid (unused and not expired) authorization code.
     */
    public Optional<AuthorizationCode> findValidCode(String code) {
        return find("code = ?1 AND used = false AND expiresAt > ?2", code, Instant.now())
            .firstResultOptional();
    }

    /**
     * Mark an authorization code as used (single-use enforcement).
     */
    @Transactional
    public void markAsUsed(String code) {
        update("used = true WHERE code = ?1", code);
    }

    /**
     * Delete all expired authorization codes (cleanup job).
     */
    @Transactional
    public long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    /**
     * Delete all codes for a principal (e.g., on logout/revoke).
     */
    @Transactional
    public long deleteByPrincipalId(Long principalId) {
        return delete("principalId", principalId);
    }
}
