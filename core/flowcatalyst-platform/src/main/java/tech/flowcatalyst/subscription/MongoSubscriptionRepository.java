package tech.flowcatalyst.subscription;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of SubscriptionRepository.
 * Package-private to prevent direct injection - use SubscriptionRepository interface.
 */
@ApplicationScoped
@Typed(SubscriptionRepository.class)
class MongoSubscriptionRepository implements SubscriptionRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<Subscription> collection() {
        return mongoClient.getDatabase(database).getCollection("subscriptions", Subscription.class);
    }

    @Override
    public Subscription findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<Subscription> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<Subscription> findByCodeAndClient(String code, String clientId) {
        Bson filter = clientId == null
            ? and(eq("code", code), eq("clientId", null))
            : and(eq("code", code), eq("clientId", clientId));
        return Optional.ofNullable(collection().find(filter).first());
    }

    @Override
    public boolean existsByCodeAndClient(String code, String clientId) {
        Bson filter = clientId == null
            ? and(eq("code", code), eq("clientId", null))
            : and(eq("code", code), eq("clientId", clientId));
        return collection().countDocuments(filter) > 0;
    }

    @Override
    public List<Subscription> findByClientId(String clientId) {
        return collection().find(eq("clientId", clientId))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<Subscription> findAnchorLevel() {
        return collection().find(eq("clientId", null))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<Subscription> findByDispatchPoolId(String dispatchPoolId) {
        return collection().find(eq("dispatchPoolId", dispatchPoolId))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public boolean existsByDispatchPoolId(String dispatchPoolId) {
        return collection().countDocuments(eq("dispatchPoolId", dispatchPoolId)) > 0;
    }

    @Override
    public List<Subscription> findByEventTypeId(String eventTypeId) {
        return collection().find(eq("eventTypes.eventTypeId", eventTypeId))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<Subscription> findByStatus(SubscriptionStatus status) {
        return collection().find(eq("status", status))
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<Subscription> findActive() {
        return findByStatus(SubscriptionStatus.ACTIVE);
    }

    @Override
    public List<Subscription> findWithFilters(String clientId, SubscriptionStatus status,
                                               SubscriptionSource source, String dispatchPoolId) {
        List<Bson> conditions = new ArrayList<>();

        if (clientId != null) {
            conditions.add(eq("clientId", clientId));
        }

        if (status != null) {
            conditions.add(eq("status", status));
        }

        if (source != null) {
            conditions.add(eq("source", source));
        }

        if (dispatchPoolId != null) {
            conditions.add(eq("dispatchPoolId", dispatchPoolId));
        }

        Bson filter = conditions.isEmpty() ? new org.bson.Document() : and(conditions);

        return collection().find(filter)
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<Subscription> findActiveByEventTypeAndClient(String eventTypeId, String clientId) {
        Bson filter = clientId == null
            ? and(
                eq("eventTypes.eventTypeId", eventTypeId),
                eq("clientId", null),
                eq("status", SubscriptionStatus.ACTIVE)
            )
            : and(
                eq("eventTypes.eventTypeId", eventTypeId),
                or(eq("clientId", clientId), eq("clientId", null)),
                eq("status", SubscriptionStatus.ACTIVE)
            );

        return collection().find(filter)
            .sort(Sorts.orderBy(Sorts.ascending("sequence"), Sorts.ascending("code")))
            .into(new ArrayList<>());
    }

    @Override
    public List<Subscription> findActiveByEventTypeCodeAndClient(String eventTypeCode, String clientId) {
        Bson filter = clientId == null
            ? and(
                eq("eventTypes.eventTypeCode", eventTypeCode),
                eq("clientId", null),
                eq("status", SubscriptionStatus.ACTIVE)
            )
            : and(
                eq("eventTypes.eventTypeCode", eventTypeCode),
                or(eq("clientId", clientId), eq("clientId", null)),
                eq("status", SubscriptionStatus.ACTIVE)
            );

        return collection().find(filter)
            .sort(Sorts.orderBy(Sorts.ascending("sequence"), Sorts.ascending("code")))
            .into(new ArrayList<>());
    }

    @Override
    public List<Subscription> listAll() {
        return collection().find()
            .sort(Sorts.ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public void persist(Subscription subscription) {
        collection().insertOne(subscription);
    }

    @Override
    public void update(Subscription subscription) {
        collection().replaceOne(eq("_id", subscription.id()), subscription);
    }

    @Override
    public void delete(Subscription subscription) {
        collection().deleteOne(eq("_id", subscription.id()));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
