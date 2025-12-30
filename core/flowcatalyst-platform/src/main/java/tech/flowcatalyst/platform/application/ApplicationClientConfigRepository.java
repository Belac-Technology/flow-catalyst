package tech.flowcatalyst.platform.application;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for ApplicationClientConfig entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface ApplicationClientConfigRepository {

    // Read operations
    ApplicationClientConfig findById(String id);
    Optional<ApplicationClientConfig> findByIdOptional(String id);
    Optional<ApplicationClientConfig> findByApplicationAndClient(String applicationId, String clientId);
    List<ApplicationClientConfig> findByApplication(String applicationId);
    List<ApplicationClientConfig> findByClient(String clientId);
    List<ApplicationClientConfig> findEnabledByClient(String clientId);
    List<ApplicationClientConfig> listAll();
    long count();
    long countByApplication(String applicationId);
    boolean isApplicationEnabledForClient(String applicationId, String clientId);

    // Write operations
    void persist(ApplicationClientConfig config);
    void update(ApplicationClientConfig config);
    void delete(ApplicationClientConfig config);
    boolean deleteById(String id);
    void deleteByApplicationAndClient(String applicationId, String clientId);
}
