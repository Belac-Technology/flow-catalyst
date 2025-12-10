package tech.flowcatalyst.platform.principal;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * Repository for AnchorDomain entities.
 */
@ApplicationScoped
public class AnchorDomainRepository implements PanacheMongoRepositoryBase<AnchorDomain, String> {

    public Optional<AnchorDomain> findByDomain(String domain) {
        return find("domain", domain).firstResultOptional();
    }

    public boolean existsByDomain(String domain) {
        return find("domain", domain).count() > 0;
    }

    public boolean isAnchorDomain(String domain) {
        return existsByDomain(domain);
    }
}
