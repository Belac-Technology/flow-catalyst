package tech.flowcatalyst.platform.authorization;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository for AuthPermission entities.
 */
@ApplicationScoped
public class AuthPermissionRepository implements PanacheMongoRepositoryBase<AuthPermission, String> {

    public Optional<AuthPermission> findByName(String name) {
        return find("name", name).firstResultOptional();
    }

    public List<AuthPermission> findByApplicationId(String applicationId) {
        return find("applicationId", applicationId).list();
    }

    public List<AuthPermission> findByApplicationCode(String applicationCode) {
        return find("applicationCode", applicationCode).list();
    }

    public boolean existsByName(String name) {
        return count("name", name) > 0;
    }

    public long deleteByName(String name) {
        return delete("name", name);
    }

    public long deleteByApplicationId(String applicationId) {
        return delete("applicationId", applicationId);
    }
}
