package tech.flowcatalyst.platform.authentication.oauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for RefreshToken entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface RefreshTokenRepository {

    // Read operations
    RefreshToken findById(String id);
    Optional<RefreshToken> findByIdOptional(String id);
    Optional<RefreshToken> findValidToken(String tokenHash);
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findActiveByPrincipalId(String principalId);
    List<RefreshToken> listAll();
    long count();

    // Write operations
    void persist(RefreshToken token);
    void update(RefreshToken token);
    void delete(RefreshToken token);
    boolean deleteById(String id);
    void revokeToken(String tokenHash, String replacedBy);
    void revokeTokenFamily(String tokenFamily);
    void revokeAllForPrincipal(String principalId);
    void revokeAllForPrincipalAndClient(String principalId, String clientId);
    long deleteExpired();
    long deleteRevokedOlderThan(Instant cutoff);
}
