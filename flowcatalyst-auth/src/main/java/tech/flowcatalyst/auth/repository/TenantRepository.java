package tech.flowcatalyst.auth.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.auth.model.Tenant;

import java.util.Optional;

/**
 * Repository for Tenant entities.
 */
@ApplicationScoped
public class TenantRepository implements PanacheRepositoryBase<Tenant, Long> {

    public Optional<Tenant> findByIdentifier(String identifier) {
        return find("identifier", identifier).firstResultOptional();
    }
}
