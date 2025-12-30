package tech.flowcatalyst.platform.client;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for ClientAccessGrant entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface ClientAccessGrantRepository {

    // Read operations
    ClientAccessGrant findById(String id);
    Optional<ClientAccessGrant> findByIdOptional(String id);
    List<ClientAccessGrant> findByPrincipalId(String principalId);
    List<ClientAccessGrant> findByClientId(String clientId);
    Optional<ClientAccessGrant> findByPrincipalIdAndClientId(String principalId, String clientId);
    List<ClientAccessGrant> listAll();
    long count();
    boolean existsByPrincipalIdAndClientId(String principalId, String clientId);

    // Write operations
    void persist(ClientAccessGrant grant);
    void update(ClientAccessGrant grant);
    void delete(ClientAccessGrant grant);
    boolean deleteById(String id);
    void deleteByPrincipalId(String principalId);
    long deleteByPrincipalIdAndClientId(String principalId, String clientId);
}
