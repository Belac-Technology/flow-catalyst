package tech.flowcatalyst.platform.principal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Principal entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface PrincipalRepository {

    // Read operations - single entity
    Principal findById(String id);
    Optional<Principal> findByIdOptional(String id);
    Optional<Principal> findByEmail(String email);
    Optional<Principal> findByServiceAccountClientId(String clientId);
    Optional<Principal> findByExternalIdpId(String externalIdpId);
    Optional<Principal> findByServiceAccountCode(String code);

    // Read operations - lists
    List<Principal> findByType(PrincipalType type);
    List<Principal> findByClientId(String clientId);
    List<Principal> findByIds(Collection<String> ids);
    List<Principal> findByClientIdAndType(String clientId, PrincipalType type);
    List<Principal> findByClientIdAndTypeAndActive(String clientId, PrincipalType type, boolean active);
    List<Principal> findByClientIdAndActive(String clientId, boolean active);
    List<Principal> findByActive(boolean active);
    List<Principal> findUsersByClientId(String clientId);
    List<Principal> findActiveUsersByClientId(String clientId);
    List<Principal> listAll();
    long count();
    long countByEmailDomain(String emailDomain);

    // Write operations
    void persist(Principal principal);
    void update(Principal principal);
    void delete(Principal principal);
    boolean deleteById(String id);
}
