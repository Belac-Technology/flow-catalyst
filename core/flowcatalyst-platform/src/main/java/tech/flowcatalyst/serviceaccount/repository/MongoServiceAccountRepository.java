package tech.flowcatalyst.serviceaccount.repository;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;
import tech.flowcatalyst.serviceaccount.entity.ServiceAccount;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MongoDB implementation of ServiceAccountRepository.
 * Package-private to prevent direct injection - use ServiceAccountRepository interface.
 */
@ApplicationScoped
@Typed(ServiceAccountRepository.class)
@Instrumented(collection = "service_accounts")
class MongoServiceAccountRepository implements PanacheMongoRepositoryBase<ServiceAccount, String>, ServiceAccountRepository {

    @Override
    public Optional<ServiceAccount> findByCode(String code) {
        return find("code", code).firstResultOptional();
    }

    @Override
    public Optional<ServiceAccount> findByApplicationId(String applicationId) {
        return find("applicationId", applicationId).firstResultOptional();
    }

    @Override
    public List<ServiceAccount> findByClientId(String clientId) {
        return find("{ $or: [{ clientIds: ?1 }, { clientIds: { $size: 0 } }, { clientIds: null }] }", clientId).list();
    }

    @Override
    public List<ServiceAccount> findActive() {
        return find("active", true).list();
    }

    @Override
    public List<ServiceAccount> findWithFilter(ServiceAccountFilter filter) {
        StringBuilder query = new StringBuilder();
        Map<String, Object> params = new HashMap<>();

        if (filter.clientId() != null) {
            query.append("{ $or: [{ clientIds: :clientId }, { clientIds: { $size: 0 } }, { clientIds: null }] }");
            params.put("clientId", filter.clientId());
        }

        if (filter.active() != null) {
            if (!query.isEmpty()) query.append(" and ");
            query.append("active = :active");
            params.put("active", filter.active());
        }

        if (filter.applicationId() != null) {
            if (!query.isEmpty()) query.append(" and ");
            query.append("applicationId = :applicationId");
            params.put("applicationId", filter.applicationId());
        }

        if (query.isEmpty()) {
            return listAll();
        }

        return find(query.toString(), params).list();
    }

    @Override
    public long countWithFilter(ServiceAccountFilter filter) {
        StringBuilder query = new StringBuilder();
        Map<String, Object> params = new HashMap<>();

        if (filter.clientId() != null) {
            query.append("{ $or: [{ clientIds: :clientId }, { clientIds: { $size: 0 } }, { clientIds: null }] }");
            params.put("clientId", filter.clientId());
        }

        if (filter.active() != null) {
            if (!query.isEmpty()) query.append(" and ");
            query.append("active = :active");
            params.put("active", filter.active());
        }

        if (filter.applicationId() != null) {
            if (!query.isEmpty()) query.append(" and ");
            query.append("applicationId = :applicationId");
            params.put("applicationId", filter.applicationId());
        }

        if (query.isEmpty()) {
            return count();
        }

        return count(query.toString(), params);
    }

    // Delegate to Panache methods via interface
    @Override
    public ServiceAccount findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<ServiceAccount> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<ServiceAccount> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(ServiceAccount serviceAccount) {
        PanacheMongoRepositoryBase.super.persist(serviceAccount);
    }

    @Override
    public void update(ServiceAccount serviceAccount) {
        PanacheMongoRepositoryBase.super.update(serviceAccount);
    }

    @Override
    public void delete(ServiceAccount serviceAccount) {
        PanacheMongoRepositoryBase.super.delete(serviceAccount);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
