package tech.flowcatalyst.platform.principal;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of AnchorDomainRepository.
 * Package-private to prevent direct injection - use AnchorDomainRepository interface.
 */
@ApplicationScoped
@Typed(AnchorDomainRepository.class)
class MongoAnchorDomainRepository implements AnchorDomainRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<AnchorDomain> collection() {
        return mongoClient.getDatabase(database).getCollection("anchor_domains", AnchorDomain.class);
    }

    @Override
    public Optional<AnchorDomain> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public List<AnchorDomain> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public boolean existsByDomain(String domain) {
        return collection().countDocuments(eq("domain", domain)) > 0;
    }

    @Override
    public void persist(AnchorDomain domain) {
        collection().insertOne(domain);
    }

    @Override
    public void delete(AnchorDomain domain) {
        collection().deleteOne(eq("_id", domain.id));
    }
}
