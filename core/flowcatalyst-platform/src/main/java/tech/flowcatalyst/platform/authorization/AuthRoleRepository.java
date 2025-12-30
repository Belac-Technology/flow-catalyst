package tech.flowcatalyst.platform.authorization;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for AuthRole entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface AuthRoleRepository {

    // Read operations
    AuthRole findById(String id);
    Optional<AuthRole> findByIdOptional(String id);
    Optional<AuthRole> findByName(String name);
    List<AuthRole> findByApplicationId(String applicationId);
    List<AuthRole> findByApplicationCode(String applicationCode);
    List<AuthRole> findBySource(AuthRole.RoleSource source);
    List<AuthRole> findClientManagedRoles();
    List<AuthRole> listAll();
    long count();
    boolean existsByName(String name);

    // Write operations
    void persist(AuthRole role);
    void update(AuthRole role);
    void delete(AuthRole role);
    boolean deleteById(String id);
    long deleteByName(String name);
    long deleteByApplicationIdAndSource(String applicationId, AuthRole.RoleSource source);
}
