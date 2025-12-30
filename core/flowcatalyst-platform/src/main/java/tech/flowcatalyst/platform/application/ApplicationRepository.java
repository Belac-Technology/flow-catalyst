package tech.flowcatalyst.platform.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Application entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 */
public interface ApplicationRepository {

    // Read operations
    Application findById(String id);
    Optional<Application> findByIdOptional(String id);
    Optional<Application> findByCode(String code);
    List<Application> findAllActive();
    List<Application> findAllActiveApplications();
    List<Application> findAllActiveIntegrations();
    List<Application> findByType(Application.ApplicationType type, boolean activeOnly);
    List<Application> findByCodes(Collection<String> codes);
    List<Application> findByIds(Collection<String> ids);
    List<Application> listAll();
    long count();
    boolean existsByCode(String code);

    // Write operations
    void persist(Application application);
    void update(Application application);
    void delete(Application application);
    boolean deleteById(String id);
}
