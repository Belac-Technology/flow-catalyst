package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class AuthorizationCodeRepository implements PanacheMongoRepositoryBase<AuthorizationCode, String> {

    /**
     * Find a valid (unused and not expired) authorization code.
     */
    public Optional<AuthorizationCode> findValidCode(String code) {
        return find("code = ?1 and used = false and expiresAt > ?2", code, Instant.now())
            .firstResultOptional();
    }

    /**
     * Mark an authorization code as used (single-use enforcement).
     */
    public void markAsUsed(String code) {
        update("used = true").where("code", code);
    }

    /**
     * Delete all expired authorization codes (cleanup job).
     */
    public long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    /**
     * Delete all codes for a principal (e.g., on logout/revoke).
     */
    public long deleteByPrincipalId(Long principalId) {
        return delete("principalId", principalId);
    }
}
