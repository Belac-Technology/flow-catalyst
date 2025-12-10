package tech.flowcatalyst.platform.application;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ApplicationClientConfig entities.
 */
@ApplicationScoped
public class ApplicationClientConfigRepository implements PanacheMongoRepositoryBase<ApplicationClientConfig, String> {

    public Optional<ApplicationClientConfig> findByApplicationAndClient(String applicationId, String clientId) {
        return find("applicationId = ?1 and clientId = ?2", applicationId, clientId).firstResultOptional();
    }

    public List<ApplicationClientConfig> findByApplication(String applicationId) {
        return list("applicationId", applicationId);
    }

    public List<ApplicationClientConfig> findByClient(String clientId) {
        return list("clientId", clientId);
    }

    public List<ApplicationClientConfig> findEnabledByClient(String clientId) {
        return list("clientId = ?1 and enabled = true", clientId);
    }

    public boolean isApplicationEnabledForClient(String applicationId, String clientId) {
        return count("applicationId = ?1 and clientId = ?2 and enabled = true", applicationId, clientId) > 0;
    }

    public void deleteByApplicationAndClient(String applicationId, String clientId) {
        delete("applicationId = ?1 and clientId = ?2", applicationId, clientId);
    }

    public long countByApplication(String applicationId) {
        return count("applicationId", applicationId);
    }
}
