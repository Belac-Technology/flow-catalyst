package tech.flowcatalyst.auth.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.auth.model.TenantAccessGrant;

import java.util.List;

/**
 * Repository for TenantAccessGrant entities.
 */
@ApplicationScoped
public class TenantAccessGrantRepository implements PanacheRepositoryBase<TenantAccessGrant, Long> {

    public List<TenantAccessGrant> findByPrincipalId(Long principalId) {
        return find("principalId", principalId).list();
    }

    public List<TenantAccessGrant> findByTenantId(Long tenantId) {
        return find("tenantId", tenantId).list();
    }
}
