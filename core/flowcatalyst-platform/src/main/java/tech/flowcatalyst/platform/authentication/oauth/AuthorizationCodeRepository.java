package tech.flowcatalyst.platform.authentication.oauth;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for AuthorizationCode entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface AuthorizationCodeRepository {

    // Read operations
    AuthorizationCode findById(String id);
    Optional<AuthorizationCode> findByIdOptional(String id);
    Optional<AuthorizationCode> findValidCode(String code);
    List<AuthorizationCode> listAll();
    long count();

    // Write operations
    void persist(AuthorizationCode code);
    void update(AuthorizationCode code);
    void delete(AuthorizationCode code);
    boolean deleteById(String id);
    void markAsUsed(String code);
    long deleteExpired();
    long deleteByPrincipalId(String principalId);
}
