package tech.flowcatalyst.serviceaccount.repository;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.serviceaccount.entity.ServiceAccount;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ServiceAccount entities.
 */
@ApplicationScoped
public class ServiceAccountRepository implements PanacheMongoRepositoryBase<ServiceAccount, String> {

    /**
     * Find service account by unique code.
     */
    public Optional<ServiceAccount> findByCode(String code) {
        return find("code", code).firstResultOptional();
    }

    /**
     * Find service account associated with an application.
     */
    public Optional<ServiceAccount> findByApplicationId(String applicationId) {
        return find("applicationId", applicationId).firstResultOptional();
    }

    /**
     * Find all service accounts that have access to a specific client.
     * Checks if clientId is in the clientIds array, or if clientIds is empty (unrestricted).
     */
    public List<ServiceAccount> findByClientId(String clientId) {
        // Find service accounts where clientIds contains the given clientId
        // or where clientIds is empty (unrestricted access)
        return find("{ $or: [{ clientIds: ?1 }, { clientIds: { $size: 0 } }, { clientIds: null }] }", clientId).list();
    }

    /**
     * Find all active service accounts.
     */
    public List<ServiceAccount> findActive() {
        return find("active", true).list();
    }

    /**
     * Find service accounts with optional filters.
     */
    public List<ServiceAccount> findWithFilter(ServiceAccountFilter filter) {
        StringBuilder query = new StringBuilder();
        java.util.Map<String, Object> params = new java.util.HashMap<>();

        // Filter by client: match if clientIds contains the value, or clientIds is empty/null
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

    /**
     * Count service accounts with optional filters.
     */
    public long countWithFilter(ServiceAccountFilter filter) {
        StringBuilder query = new StringBuilder();
        java.util.Map<String, Object> params = new java.util.HashMap<>();

        // Filter by client: match if clientIds contains the value, or clientIds is empty/null
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

    /**
     * Filter for service account queries.
     */
    public record ServiceAccountFilter(
        String clientId,
        Boolean active,
        String applicationId
    ) {
        public static ServiceAccountFilter all() {
            return new ServiceAccountFilter(null, null, null);
        }

        public static ServiceAccountFilter forClient(String clientId) {
            return new ServiceAccountFilter(clientId, null, null);
        }

        public static ServiceAccountFilter activeOnly() {
            return new ServiceAccountFilter(null, true, null);
        }
    }
}
