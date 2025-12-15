package tech.flowcatalyst.platform.application;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Application entities.
 */
@ApplicationScoped
public class ApplicationRepository implements PanacheMongoRepositoryBase<Application, String> {

    public Optional<Application> findByCode(String code) {
        return find("code", code).firstResultOptional();
    }

    public List<Application> findAllActive() {
        return list("active", true);
    }

    /**
     * Find all active user-facing applications (type = APPLICATION).
     * Use this when populating dropdowns for assigning apps to clients/users.
     */
    public List<Application> findAllActiveApplications() {
        return list("active = ?1 and type = ?2", true, Application.ApplicationType.APPLICATION);
    }

    /**
     * Find all active integrations (type = INTEGRATION).
     */
    public List<Application> findAllActiveIntegrations() {
        return list("active = ?1 and type = ?2", true, Application.ApplicationType.INTEGRATION);
    }

    /**
     * Find all applications/integrations by type.
     */
    public List<Application> findByType(Application.ApplicationType type, boolean activeOnly) {
        if (activeOnly) {
            return list("type = ?1 and active = ?2", type, true);
        }
        return list("type", type);
    }

    public List<Application> findByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return list("code in ?1 and active", codes, true);
    }

    public boolean existsByCode(String code) {
        return count("code", code) > 0;
    }
}
