package tech.flowcatalyst.platform.authentication.oauth;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for OAuthClient entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface OAuthClientRepository {

    // Read operations
    OAuthClient findById(String id);
    Optional<OAuthClient> findByIdOptional(String id);
    Optional<OAuthClient> findByClientId(String clientId);
    Optional<OAuthClient> findByClientIdIncludingInactive(String clientId);
    List<OAuthClient> findByTenantId(Long tenantId);
    List<OAuthClient> findAllActive();
    List<OAuthClient> findByApplicationIdAndActive(String applicationId, boolean active);
    List<OAuthClient> findByApplicationId(String applicationId);
    List<OAuthClient> findByActive(boolean active);
    List<OAuthClient> listAll();
    long count();

    // Write operations
    void persist(OAuthClient client);
    void update(OAuthClient client);
    void delete(OAuthClient client);
    boolean deleteById(String id);
    long deleteByServiceAccountPrincipalId(String principalId);
}
