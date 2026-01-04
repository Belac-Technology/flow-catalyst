package tech.flowcatalyst.serviceaccount.repository;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.flowcatalyst.serviceaccount.entity.ServiceAccount;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of ServiceAccountRepository.
 * Package-private to prevent direct injection - use ServiceAccountRepository interface.
 */
@ApplicationScoped
@Typed(ServiceAccountRepository.class)
class MongoServiceAccountRepository implements ServiceAccountRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<ServiceAccount> collection() {
        return mongoClient.getDatabase(database).getCollection("service_accounts", ServiceAccount.class);
    }

    @Override
    public ServiceAccount findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<ServiceAccount> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<ServiceAccount> findByCode(String code) {
        return Optional.ofNullable(collection().find(eq("code", code)).first());
    }

    @Override
    public Optional<ServiceAccount> findByApplicationId(String applicationId) {
        return Optional.ofNullable(collection().find(eq("applicationId", applicationId)).first());
    }

    @Override
    public List<ServiceAccount> findByClientId(String clientId) {
        // Find service accounts where:
        // - clientIds contains the specified clientId, OR
        // - clientIds is empty (size 0), OR
        // - clientIds is null
        Bson filter = or(
            eq("clientIds", clientId),
            size("clientIds", 0),
            eq("clientIds", null)
        );
        return collection().find(filter).into(new ArrayList<>());
    }

    @Override
    public List<ServiceAccount> findActive() {
        return collection().find(eq("active", true)).into(new ArrayList<>());
    }

    @Override
    public List<ServiceAccount> findWithFilter(ServiceAccountFilter filter) {
        List<Bson> conditions = new ArrayList<>();

        if (filter.clientId() != null) {
            // Same logic as findByClientId
            conditions.add(or(
                eq("clientIds", filter.clientId()),
                size("clientIds", 0),
                eq("clientIds", null)
            ));
        }

        if (filter.active() != null) {
            conditions.add(eq("active", filter.active()));
        }

        if (filter.applicationId() != null) {
            conditions.add(eq("applicationId", filter.applicationId()));
        }

        if (conditions.isEmpty()) {
            return collection().find().into(new ArrayList<>());
        }

        return collection().find(and(conditions)).into(new ArrayList<>());
    }

    @Override
    public long countWithFilter(ServiceAccountFilter filter) {
        List<Bson> conditions = new ArrayList<>();

        if (filter.clientId() != null) {
            conditions.add(or(
                eq("clientIds", filter.clientId()),
                size("clientIds", 0),
                eq("clientIds", null)
            ));
        }

        if (filter.active() != null) {
            conditions.add(eq("active", filter.active()));
        }

        if (filter.applicationId() != null) {
            conditions.add(eq("applicationId", filter.applicationId()));
        }

        if (conditions.isEmpty()) {
            return collection().countDocuments();
        }

        return collection().countDocuments(and(conditions));
    }

    @Override
    public List<ServiceAccount> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public void persist(ServiceAccount serviceAccount) {
        collection().insertOne(serviceAccount);
    }

    @Override
    public void update(ServiceAccount serviceAccount) {
        collection().replaceOne(eq("_id", serviceAccount.id), serviceAccount);
    }

    @Override
    public void delete(ServiceAccount serviceAccount) {
        collection().deleteOne(eq("_id", serviceAccount.id));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
