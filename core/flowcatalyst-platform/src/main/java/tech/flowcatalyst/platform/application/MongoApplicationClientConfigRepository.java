package tech.flowcatalyst.platform.application;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of ApplicationClientConfigRepository.
 * Package-private to prevent direct injection - use ApplicationClientConfigRepository interface.
 */
@ApplicationScoped
@Typed(ApplicationClientConfigRepository.class)
@Instrumented(collection = "application_client_configs")
class MongoApplicationClientConfigRepository implements PanacheMongoRepositoryBase<ApplicationClientConfig, String>, ApplicationClientConfigRepository {

    @Override
    public Optional<ApplicationClientConfig> findByApplicationAndClient(String applicationId, String clientId) {
        return find("applicationId = ?1 and clientId = ?2", applicationId, clientId).firstResultOptional();
    }

    @Override
    public List<ApplicationClientConfig> findByApplication(String applicationId) {
        return list("applicationId", applicationId);
    }

    @Override
    public List<ApplicationClientConfig> findByClient(String clientId) {
        return list("clientId", clientId);
    }

    @Override
    public List<ApplicationClientConfig> findEnabledByClient(String clientId) {
        return list("clientId = ?1 and enabled = true", clientId);
    }

    @Override
    public boolean isApplicationEnabledForClient(String applicationId, String clientId) {
        return count("applicationId = ?1 and clientId = ?2 and enabled = true", applicationId, clientId) > 0;
    }

    @Override
    public void deleteByApplicationAndClient(String applicationId, String clientId) {
        delete("applicationId = ?1 and clientId = ?2", applicationId, clientId);
    }

    @Override
    public long countByApplication(String applicationId) {
        return count("applicationId", applicationId);
    }

    // Delegate to Panache methods via interface
    @Override
    public ApplicationClientConfig findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<ApplicationClientConfig> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<ApplicationClientConfig> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(ApplicationClientConfig config) {
        PanacheMongoRepositoryBase.super.persist(config);
    }

    @Override
    public void update(ApplicationClientConfig config) {
        PanacheMongoRepositoryBase.super.update(config);
    }

    @Override
    public void delete(ApplicationClientConfig config) {
        PanacheMongoRepositoryBase.super.delete(config);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
