package tech.flowcatalyst.auth.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.auth.model.AnchorDomain;

import java.util.Optional;

/**
 * Repository for AnchorDomain entities.
 */
@ApplicationScoped
public class AnchorDomainRepository implements PanacheRepositoryBase<AnchorDomain, Long> {

    public Optional<AnchorDomain> findByDomain(String domain) {
        return find("domain", domain).firstResultOptional();
    }

    public boolean isAnchorDomain(String domain) {
        return find("domain", domain).count() > 0;
    }
}
