package tech.flowcatalyst.subscription;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of SubscriptionRepository.
 * Package-private to prevent direct injection - use SubscriptionRepository interface.
 */
@ApplicationScoped
@Typed(SubscriptionRepository.class)
@Instrumented(collection = "subscriptions")
class MongoSubscriptionRepository implements PanacheMongoRepositoryBase<Subscription, String>, SubscriptionRepository {

    @Override
    public Optional<Subscription> findByCodeAndClient(String code, String clientId) {
        if (clientId == null) {
            return find("code = ?1 and clientId = null", code).firstResultOptional();
        }
        return find("code = ?1 and clientId = ?2", code, clientId).firstResultOptional();
    }

    @Override
    public boolean existsByCodeAndClient(String code, String clientId) {
        if (clientId == null) {
            return count("code = ?1 and clientId = null", code) > 0;
        }
        return count("code = ?1 and clientId = ?2", code, clientId) > 0;
    }

    @Override
    public List<Subscription> findByClientId(String clientId) {
        return list("clientId", Sort.by("code"), clientId);
    }

    @Override
    public List<Subscription> findAnchorLevel() {
        return list("clientId = null", Sort.by("code"));
    }

    @Override
    public List<Subscription> findByDispatchPoolId(String dispatchPoolId) {
        return list("dispatchPoolId", Sort.by("code"), dispatchPoolId);
    }

    @Override
    public boolean existsByDispatchPoolId(String dispatchPoolId) {
        return count("dispatchPoolId", dispatchPoolId) > 0;
    }

    @Override
    public List<Subscription> findByEventTypeId(String eventTypeId) {
        return list("eventTypes.eventTypeId", Sort.by("code"), eventTypeId);
    }

    @Override
    public List<Subscription> findByStatus(SubscriptionStatus status) {
        return list("status", Sort.by("code"), status);
    }

    @Override
    public List<Subscription> findActive() {
        return findByStatus(SubscriptionStatus.ACTIVE);
    }

    @Override
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

    @Override
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

    @Override
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

    // Delegate to Panache methods via interface
    @Override
    public Subscription findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<Subscription> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<Subscription> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(Subscription subscription) {
        PanacheMongoRepositoryBase.super.persist(subscription);
    }

    @Override
    public void update(Subscription subscription) {
        PanacheMongoRepositoryBase.super.update(subscription);
    }

    @Override
    public void delete(Subscription subscription) {
        PanacheMongoRepositoryBase.super.delete(subscription);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
