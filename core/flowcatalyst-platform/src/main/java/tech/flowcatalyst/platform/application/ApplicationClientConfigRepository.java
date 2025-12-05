package tech.flowcatalyst.platform.application;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ApplicationClientConfig entities.
 */
@ApplicationScoped
public class ApplicationClientConfigRepository implements PanacheRepositoryBase<ApplicationClientConfig, Long> {

    public Optional<ApplicationClientConfig> findByApplicationAndClient(Long applicationId, Long clientId) {
        return find("application.id = ?1 and client.id = ?2", applicationId, clientId).firstResultOptional();
    }

    public List<ApplicationClientConfig> findByApplication(Long applicationId) {
        return list("application.id = ?1", applicationId);
    }

    public List<ApplicationClientConfig> findByClient(Long clientId) {
        return list("client.id = ?1", clientId);
    }

    public List<ApplicationClientConfig> findEnabledByClient(Long clientId) {
        return list("client.id = ?1 and enabled = true", clientId);
    }

    public boolean isApplicationEnabledForClient(Long applicationId, Long clientId) {
        return count("application.id = ?1 and client.id = ?2 and enabled = true", applicationId, clientId) > 0;
    }

    public void deleteByApplicationAndClient(Long applicationId, Long clientId) {
        delete("application.id = ?1 and client.id = ?2", applicationId, clientId);
    }

    public long countByApplication(Long applicationId) {
        return count("application.id = ?1", applicationId);
    }
}
