package tech.flowcatalyst.dispatchpool;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository for DispatchPool entities.
 */
@ApplicationScoped
public class DispatchPoolRepository implements PanacheMongoRepositoryBase<DispatchPool, String> {

    /**
     * Find a pool by its code within a specific client scope.
     *
     * @param code The pool code
     * @param clientId The client ID (null for anchor-level pools)
     * @return The pool if found
     */
    public Optional<DispatchPool> findByCodeAndClientId(String code, String clientId) {
        if (clientId == null) {
            return find("code = ?1 and clientId = null", code).firstResultOptional();
        }
        return find("code = ?1 and clientId = ?2", code, clientId).firstResultOptional();
    }

    /**
     * Check if a pool with the given code exists in the specified scope.
     *
     * @param code The pool code
     * @param clientId The client ID (null for anchor-level pools)
     * @return true if a pool exists with this code in the scope
     */
    public boolean existsByCodeAndClientId(String code, String clientId) {
        if (clientId == null) {
            return count("code = ?1 and clientId = null", code) > 0;
        }
        return count("code = ?1 and clientId = ?2", code, clientId) > 0;
    }

    /**
     * Find all pools for a specific client.
     *
     * @param clientId The client ID
     * @return List of pools for the client
     */
    public List<DispatchPool> findByClientId(String clientId) {
        return list("clientId", Sort.by("code"), clientId);
    }

    /**
     * Find all anchor-level pools (not client-specific).
     *
     * @return List of anchor-level pools
     */
    public List<DispatchPool> findAnchorLevel() {
        return list("clientId = null", Sort.by("code"));
    }

    /**
     * Find all pools with a specific status.
     *
     * @param status The status to filter by
     * @return List of pools with the status
     */
    public List<DispatchPool> findByStatus(DispatchPoolStatus status) {
        return list("status", Sort.by("code"), status);
    }

    /**
     * Find all active pools.
     *
     * @return List of active pools
     */
    public List<DispatchPool> findActive() {
        return findByStatus(DispatchPoolStatus.ACTIVE);
    }

    /**
     * Find all non-archived pools.
     *
     * @return List of non-archived pools
     */
    public List<DispatchPool> findAllNonArchived() {
        return list("status != ?1", Sort.by("code"), DispatchPoolStatus.ARCHIVED);
    }

    /**
     * Find all pools with optional filters.
     *
     * @param clientId Filter by client ID (null to skip)
     * @param status Filter by status (null to skip)
     * @param includeArchived Whether to include archived pools
     * @return List of pools matching the filters
     */
    public List<DispatchPool> findWithFilters(String clientId, DispatchPoolStatus status, boolean includeArchived) {
        StringBuilder query = new StringBuilder();
        java.util.List<Object> params = new java.util.ArrayList<>();
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
}
