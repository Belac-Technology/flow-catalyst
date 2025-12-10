package tech.flowcatalyst.platform.authorization;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository for AuthRole entities.
 */
@ApplicationScoped
public class AuthRoleRepository implements PanacheMongoRepositoryBase<AuthRole, String> {

    public Optional<AuthRole> findByName(String name) {
        return find("name", name).firstResultOptional();
    }

    public List<AuthRole> findByApplicationId(String applicationId) {
        return find("applicationId", applicationId).list();
    }

    public List<AuthRole> findByApplicationCode(String applicationCode) {
        return find("applicationCode", applicationCode).list();
    }

    public List<AuthRole> findBySource(AuthRole.RoleSource source) {
        return find("source", source).list();
    }

    public List<AuthRole> findClientManagedRoles() {
        return find("clientManaged", true).list();
    }

    public boolean existsByName(String name) {
        return count("name", name) > 0;
    }

    public long deleteByName(String name) {
        return delete("name", name);
    }

    public long deleteByApplicationIdAndSource(String applicationId, AuthRole.RoleSource source) {
        return delete("applicationId = ?1 and source = ?2", applicationId, source);
    }
}
