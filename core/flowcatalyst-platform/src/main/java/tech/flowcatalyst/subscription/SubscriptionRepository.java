package tech.flowcatalyst.subscription;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Subscription entities.
 */
@ApplicationScoped
public class SubscriptionRepository implements PanacheMongoRepositoryBase<Subscription, String> {

    /**
     * Find a subscription by its code within a client scope.
     *
     * @param code The subscription code
     * @param clientId The client ID (null for anchor-level subscriptions)
     * @return The subscription if found
     */
    public Optional<Subscription> findByCodeAndClient(String code, String clientId) {
        if (clientId == null) {
            return find("code = ?1 and clientId = null", code).firstResultOptional();
        }
        return find("code = ?1 and clientId = ?2", code, clientId).firstResultOptional();
    }

    /**
     * Check if a subscription with the given code exists in the specified client scope.
     *
     * @param code The subscription code
     * @param clientId The client ID (null for anchor-level subscriptions)
     * @return true if a subscription exists with this code
     */
    public boolean existsByCodeAndClient(String code, String clientId) {
        if (clientId == null) {
            return count("code = ?1 and clientId = null", code) > 0;
        }
        return count("code = ?1 and clientId = ?2", code, clientId) > 0;
    }

    /**
     * Find all subscriptions for a specific client.
     *
     * @param clientId The client ID
     * @return List of subscriptions for the client
     */
    public List<Subscription> findByClientId(String clientId) {
        return list("clientId", Sort.by("code"), clientId);
    }

    /**
     * Find all anchor-level subscriptions (not client-specific).
     *
     * @return List of anchor-level subscriptions
     */
    public List<Subscription> findAnchorLevel() {
        return list("clientId = null", Sort.by("code"));
    }

    /**
     * Find all subscriptions for a specific dispatch pool.
     *
     * @param dispatchPoolId The dispatch pool ID
     * @return List of subscriptions using this pool
     */
    public List<Subscription> findByDispatchPoolId(String dispatchPoolId) {
        return list("dispatchPoolId", Sort.by("code"), dispatchPoolId);
    }

    /**
     * Check if any subscriptions are using a specific dispatch pool.
     *
     * @param dispatchPoolId The dispatch pool ID
     * @return true if any subscriptions are using this pool
     */
    public boolean existsByDispatchPoolId(String dispatchPoolId) {
        return count("dispatchPoolId", dispatchPoolId) > 0;
    }

    /**
     * Find all subscriptions that listen to a specific event type.
     *
     * @param eventTypeId The event type ID
     * @return List of subscriptions listening to this event type
     */
    public List<Subscription> findByEventTypeId(String eventTypeId) {
        return list("eventTypes.eventTypeId", Sort.by("code"), eventTypeId);
    }

    /**
     * Find all subscriptions with a specific status.
     *
     * @param status The status to filter by
     * @return List of subscriptions with the status
     */
    public List<Subscription> findByStatus(SubscriptionStatus status) {
        return list("status", Sort.by("code"), status);
    }

    /**
     * Find all active subscriptions.
     *
     * @return List of active subscriptions
     */
    public List<Subscription> findActive() {
        return findByStatus(SubscriptionStatus.ACTIVE);
    }

    /**
     * Find all subscriptions with optional filters.
     *
     * @param clientId Filter by client ID (null to skip)
     * @param status Filter by status (null to skip)
     * @param source Filter by source (null to skip)
     * @param dispatchPoolId Filter by dispatch pool (null to skip)
     * @return List of subscriptions matching the filters
     */
    public List<Subscription> findWithFilters(String clientId, SubscriptionStatus status,
                                               SubscriptionSource source, String dispatchPoolId) {
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
        }

        if (source != null) {
            if (!query.isEmpty()) query.append(" and ");
            query.append("source = ?").append(paramIndex++);
            params.add(source);
        }

        if (dispatchPoolId != null) {
            if (!query.isEmpty()) query.append(" and ");
            query.append("dispatchPoolId = ?").append(paramIndex++);
            params.add(dispatchPoolId);
        }

        if (query.isEmpty()) {
            return listAll(Sort.by("code"));
        }

        return list(query.toString(), Sort.by("code"), params.toArray());
    }

    /**
     * Find active subscriptions for a specific event type ID and client.
     * Used for matching events to subscriptions.
     *
     * @param eventTypeId The event type ID
     * @param clientId The client ID (null for anchor-level)
     * @return List of active subscriptions matching the criteria
     */
    public List<Subscription> findActiveByEventTypeAndClient(String eventTypeId, String clientId) {
        if (clientId == null) {
            return list("eventTypes.eventTypeId = ?1 and clientId = null and status = ?2",
                Sort.by("sequence").and("code"),
                eventTypeId, SubscriptionStatus.ACTIVE);
        }
        return list("eventTypes.eventTypeId = ?1 and (clientId = ?2 or clientId = null) and status = ?3",
            Sort.by("sequence").and("code"),
            eventTypeId, clientId, SubscriptionStatus.ACTIVE);
    }

    /**
     * Find active subscriptions for a specific event type code and client.
     * Used for matching events to subscriptions during dispatch job creation.
     *
     * @param eventTypeCode The event type code (e.g., "operant:execution:trip:started")
     * @param clientId The client ID (null for anchor-level)
     * @return List of active subscriptions matching the criteria
     */
    public List<Subscription> findActiveByEventTypeCodeAndClient(String eventTypeCode, String clientId) {
        if (clientId == null) {
            return list("eventTypes.eventTypeCode = ?1 and clientId = null and status = ?2",
                Sort.by("sequence").and("code"),
                eventTypeCode, SubscriptionStatus.ACTIVE);
        }
        return list("eventTypes.eventTypeCode = ?1 and (clientId = ?2 or clientId = null) and status = ?3",
            Sort.by("sequence").and("code"),
            eventTypeCode, clientId, SubscriptionStatus.ACTIVE);
    }
}
