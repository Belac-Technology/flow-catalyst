package tech.flowcatalyst.platform.principal;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of AnchorDomainRepository.
 * Package-private to prevent direct injection - use AnchorDomainRepository interface.
 */
@ApplicationScoped
@Typed(AnchorDomainRepository.class)
@Instrumented(collection = "anchor_domains")
class MongoAnchorDomainRepository implements PanacheMongoRepositoryBase<AnchorDomain, String>, AnchorDomainRepository {

    @Override
    public Optional<AnchorDomain> findByDomain(String domain) {
        return find("domain", domain).firstResultOptional();
    }

    @Override
    public boolean existsByDomain(String domain) {
        return find("domain", domain).count() > 0;
    }

    @Override
    public boolean isAnchorDomain(String domain) {
        return existsByDomain(domain);
    }

    // Delegate to Panache methods via interface
    @Override
    public AnchorDomain findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<AnchorDomain> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<AnchorDomain> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(AnchorDomain domain) {
        PanacheMongoRepositoryBase.super.persist(domain);
    }

    @Override
    public void update(AnchorDomain domain) {
        PanacheMongoRepositoryBase.super.update(domain);
    }

    @Override
    public void delete(AnchorDomain domain) {
        PanacheMongoRepositoryBase.super.delete(domain);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
