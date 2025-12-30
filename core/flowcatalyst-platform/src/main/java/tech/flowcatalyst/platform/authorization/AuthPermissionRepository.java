package tech.flowcatalyst.platform.authorization;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for AuthPermission entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface AuthPermissionRepository {

    // Read operations
    AuthPermission findById(String id);
    Optional<AuthPermission> findByIdOptional(String id);
    Optional<AuthPermission> findByName(String name);
    List<AuthPermission> findByApplicationId(String applicationId);
    List<AuthPermission> findByApplicationCode(String applicationCode);
    List<AuthPermission> listAll();
    long count();
    boolean existsByName(String name);

    // Write operations
    void persist(AuthPermission permission);
    void update(AuthPermission permission);
    void delete(AuthPermission permission);
    boolean deleteById(String id);
    long deleteByName(String name);
    long deleteByApplicationId(String applicationId);
}
