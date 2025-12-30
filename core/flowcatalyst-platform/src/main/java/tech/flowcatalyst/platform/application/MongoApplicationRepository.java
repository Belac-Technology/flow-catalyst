package tech.flowcatalyst.platform.application;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of ApplicationRepository.
 * Package-private to prevent direct injection - use ApplicationRepository interface.
 */
@ApplicationScoped
@Typed(ApplicationRepository.class)
@Instrumented(collection = "applications")
class MongoApplicationRepository implements PanacheMongoRepositoryBase<Application, String>, ApplicationRepository {

    @Override
    public Optional<Application> findByCode(String code) {
        return find("code", code).firstResultOptional();
    }

    @Override
    public List<Application> findAllActive() {
        return list("active", true);
    }

    @Override
    public List<Application> findAllActiveApplications() {
        return list("active = ?1 and type = ?2", true, Application.ApplicationType.APPLICATION);
    }

    @Override
    public List<Application> findAllActiveIntegrations() {
        return list("active = ?1 and type = ?2", true, Application.ApplicationType.INTEGRATION);
    }

    @Override
    public List<Application> findByType(Application.ApplicationType type, boolean activeOnly) {
        if (activeOnly) {
            return list("type = ?1 and active = ?2", type, true);
        }
        return list("type", type);
    }

    @Override
    public List<Application> findByCodes(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return list("code in ?1 and active", codes, true);
    }

    @Override
    public List<Application> findByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return list("_id in ?1", ids);
    }

    @Override
    public boolean existsByCode(String code) {
        return count("code", code) > 0;
    }

    // Delegate to Panache methods via interface
    @Override
    public Application findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<Application> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<Application> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(Application application) {
        PanacheMongoRepositoryBase.super.persist(application);
    }

    @Override
    public void update(Application application) {
        PanacheMongoRepositoryBase.super.update(application);
    }

    @Override
    public void delete(Application application) {
        PanacheMongoRepositoryBase.super.delete(application);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
