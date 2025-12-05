package tech.flowcatalyst.platform.authorization;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository for AuthPermission entities.
 */
@ApplicationScoped
public class AuthPermissionRepository implements PanacheRepositoryBase<AuthPermission, Long> {

    public Optional<AuthPermission> findByName(String name) {
        return find("name", name).firstResultOptional();
    }

    public List<AuthPermission> findByApplicationId(Long applicationId) {
        return find("application.id", applicationId).list();
    }

    public List<AuthPermission> findByApplicationCode(String applicationCode) {
        return find("application.code", applicationCode).list();
    }

    public boolean existsByName(String name) {
        return count("name", name) > 0;
    }

    public long deleteByName(String name) {
        return delete("name", name);
    }

    public long deleteByApplicationId(Long applicationId) {
        return delete("application.id", applicationId);
    }
}
