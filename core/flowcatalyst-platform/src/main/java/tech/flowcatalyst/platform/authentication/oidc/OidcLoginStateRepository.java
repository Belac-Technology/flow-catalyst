package tech.flowcatalyst.platform.authentication.oidc;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for OidcLoginState entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface OidcLoginStateRepository {

    // Read operations
    OidcLoginState findById(String id);
    Optional<OidcLoginState> findByIdOptional(String id);
    Optional<OidcLoginState> findValidState(String state);
    List<OidcLoginState> listAll();
    long count();

    // Write operations
    void persist(OidcLoginState state);
    void update(OidcLoginState state);
    void delete(OidcLoginState state);
    boolean deleteById(String id);
    void deleteExpired();
    void deleteByState(String state);
}
