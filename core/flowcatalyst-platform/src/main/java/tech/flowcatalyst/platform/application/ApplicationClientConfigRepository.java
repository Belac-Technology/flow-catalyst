package tech.flowcatalyst.platform.application;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ApplicationClientConfig entities.
 */
@ApplicationScoped
public class ApplicationClientConfigRepository implements PanacheMongoRepositoryBase<ApplicationClientConfig, Long> {

    public Optional<ApplicationClientConfig> findByApplicationAndClient(Long applicationId, Long clientId) {
        return find("applicationId = ?1 and clientId = ?2", applicationId, clientId).firstResultOptional();
    }

    public List<ApplicationClientConfig> findByApplication(Long applicationId) {
        return list("applicationId", applicationId);
    }

    public List<ApplicationClientConfig> findByClient(Long clientId) {
        return list("clientId", clientId);
    }

    public List<ApplicationClientConfig> findEnabledByClient(Long clientId) {
        return list("clientId = ?1 and enabled = true", clientId);
    }

    public boolean isApplicationEnabledForClient(Long applicationId, Long clientId) {
        return count("applicationId = ?1 and clientId = ?2 and enabled = true", applicationId, clientId) > 0;
    }

    public void deleteByApplicationAndClient(Long applicationId, Long clientId) {
        delete("applicationId = ?1 and clientId = ?2", applicationId, clientId);
    }

    public long countByApplication(Long applicationId) {
        return count("applicationId", applicationId);
    }
}
