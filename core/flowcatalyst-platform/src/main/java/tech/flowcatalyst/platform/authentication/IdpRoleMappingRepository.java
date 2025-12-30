package tech.flowcatalyst.platform.authentication;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for IdpRoleMapping entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 * SECURITY: Only explicitly authorized IDP roles should exist in this table.
 */
public interface IdpRoleMappingRepository {

    // Read operations
    IdpRoleMapping findById(String id);
    Optional<IdpRoleMapping> findByIdOptional(String id);
    Optional<IdpRoleMapping> findByIdpRoleName(String idpRoleName);
    List<IdpRoleMapping> listAll();
    long count();

    // Write operations
    void persist(IdpRoleMapping mapping);
    void update(IdpRoleMapping mapping);
    void delete(IdpRoleMapping mapping);
    boolean deleteById(String id);
}
