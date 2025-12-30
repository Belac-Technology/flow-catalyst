package tech.flowcatalyst.platform.client;

import tech.flowcatalyst.platform.authentication.AuthProvider;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for ClientAuthConfig entities.
 * Used to look up authentication configuration by email domain.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface ClientAuthConfigRepository {

    // Read operations
    ClientAuthConfig findById(String id);
    Optional<ClientAuthConfig> findByIdOptional(String id);
    Optional<ClientAuthConfig> findByEmailDomain(String emailDomain);
    List<ClientAuthConfig> findByAuthProvider(AuthProvider provider);
    List<ClientAuthConfig> findByClientId(String clientId);
    List<ClientAuthConfig> findByConfigType(AuthConfigType configType);
    List<ClientAuthConfig> listAll();
    long count();
    boolean existsByEmailDomain(String emailDomain);

    // Write operations
    void persist(ClientAuthConfig config);
    void update(ClientAuthConfig config);
    void delete(ClientAuthConfig config);
    boolean deleteById(String id);
}
