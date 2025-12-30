package tech.flowcatalyst.dispatchpool;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of DispatchPoolRepository.
 * Package-private to prevent direct injection - use DispatchPoolRepository interface.
 */
@ApplicationScoped
@Typed(DispatchPoolRepository.class)
@Instrumented(collection = "dispatch_pools")
class MongoDispatchPoolRepository implements PanacheMongoRepositoryBase<DispatchPool, String>, DispatchPoolRepository {

    @Override
    public Optional<DispatchPool> findByCodeAndClientId(String code, String clientId) {
        if (clientId == null) {
            return find("code = ?1 and clientId = null", code).firstResultOptional();
        }
        return find("code = ?1 and clientId = ?2", code, clientId).firstResultOptional();
    }

    @Override
    public boolean existsByCodeAndClientId(String code, String clientId) {
        if (clientId == null) {
            return count("code = ?1 and clientId = null", code) > 0;
        }
        return count("code = ?1 and clientId = ?2", code, clientId) > 0;
    }

    @Override
    public List<DispatchPool> findByClientId(String clientId) {
        return list("clientId", Sort.by("code"), clientId);
    }

    @Override
    public List<DispatchPool> findAnchorLevel() {
        return list("clientId = null", Sort.by("code"));
    }

    @Override
    public List<DispatchPool> findByStatus(DispatchPoolStatus status) {
        return list("status", Sort.by("code"), status);
    }

    @Override
    public List<DispatchPool> findActive() {
        return findByStatus(DispatchPoolStatus.ACTIVE);
    }

    @Override
    public List<DispatchPool> findAllNonArchived() {
        return list("status != ?1", Sort.by("code"), DispatchPoolStatus.ARCHIVED);
    }

    @Override
    public List<DispatchPool> findWithFilters(String clientId, DispatchPoolStatus status, boolean includeArchived) {
        StringBuilder query = new StringBuilder();
        List<Object> params = new ArrayList<>();
        int paramIndex = 1;

        if (clientId != null) {
            query.append("clientId = ?").append(paramIndex++);
            params.add(clientId);
        }

        if (status != null) {
            if (!query.isEmpty()) query.append(" and ");
            query.append("status = ?").append(paramIndex++);
            params.add(status);
        } else if (!includeArchived) {
            if (!query.isEmpty()) query.append(" and ");
            query.append("status != ?").append(paramIndex++);
            params.add(DispatchPoolStatus.ARCHIVED);
        }

        if (query.isEmpty()) {
            return listAll(Sort.by("code"));
        }

        return list(query.toString(), Sort.by("code"), params.toArray());
    }

    // Delegate to Panache methods via interface
    @Override
    public DispatchPool findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<DispatchPool> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<DispatchPool> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(DispatchPool pool) {
        PanacheMongoRepositoryBase.super.persist(pool);
    }

    @Override
    public void update(DispatchPool pool) {
        PanacheMongoRepositoryBase.super.update(pool);
    }

    @Override
    public void delete(DispatchPool pool) {
        PanacheMongoRepositoryBase.super.delete(pool);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
