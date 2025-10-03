package tech.flowcatalyst.platform.tenant;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.platform.tenant.Tenant;
import tech.flowcatalyst.platform.tenant.TenantStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for Tenant entities.
 */
@ApplicationScoped
public class TenantRepository implements PanacheRepositoryBase<Tenant, Long> {

    public Optional<Tenant> findByIdentifier(String identifier) {
        return find("identifier", identifier).firstResultOptional();
    }

    public Optional<Tenant> findBySlug(String slug) {
        return find("slug", slug).firstResultOptional();
    }

    public List<Tenant> findAllActive() {
        return find("status", TenantStatus.ACTIVE).list();
    }

    public List<Tenant> findByIds(Set<Long> ids) {
        return find("id in ?1", ids).list();
    }
}
