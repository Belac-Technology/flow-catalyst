package tech.flowcatalyst.platform.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalType;
import tech.flowcatalyst.platform.tenant.Tenant;
import tech.flowcatalyst.platform.tenant.TenantAccessGrant;
import tech.flowcatalyst.platform.principal.AnchorDomainRepository;
import tech.flowcatalyst.platform.tenant.TenantAccessGrantRepository;
import tech.flowcatalyst.platform.tenant.TenantRepository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for calculating which tenants a principal can access.
 *
 * Access rules:
 * 1. Anchor domain users → ALL tenants (global access)
 * 2. Home tenant (principal.tenantId) if exists
 * 3. Explicitly granted tenants (TenantAccessGrant)
 */
@ApplicationScoped
public class TenantAccessService {

    @Inject
    TenantRepository tenantRepo;

    @Inject
    AnchorDomainRepository anchorDomainRepo;

    @Inject
    TenantAccessGrantRepository grantRepo;

    /**
     * Calculate which tenants a principal can access.
     *
     * @param principal The principal (user or service account)
     * @return Set of accessible tenant IDs
     */
    public Set<Long> getAccessibleTenants(Principal principal) {
        Set<Long> tenantIds = new HashSet<>();

        // 1. Check if anchor domain user (global access)
        if (principal.type == PrincipalType.USER && principal.userIdentity != null) {
            String domain = principal.userIdentity.emailDomain;
            if (domain != null && anchorDomainRepo.existsByDomain(domain)) {
                // Return ALL active tenant IDs
                return tenantRepo.findAllActive().stream()
                    .map(t -> t.id)
                    .collect(Collectors.toSet());
            }
        }

        // 2. Add home tenant if exists and is active
        if (principal.tenantId != null) {
            tenantIds.add(principal.tenantId);
        }

        // 3. Add explicitly granted tenants (filter expired grants)
        List<TenantAccessGrant> grants = grantRepo.findByPrincipalId(principal.id);
        Instant now = Instant.now();

        grants.stream()
            .filter(g -> g.expiresAt == null || g.expiresAt.isAfter(now))
            .map(g -> g.tenantId)
            .forEach(tenantIds::add);

        // 4. Filter out inactive/suspended tenants
        if (!tenantIds.isEmpty()) {
            List<Tenant> tenants = tenantRepo.findByIds(tenantIds);
            return tenants.stream()
                .filter(t -> t.status == TenantStatus.ACTIVE)
                .map(t -> t.id)
                .collect(Collectors.toSet());
        }

        return tenantIds;
    }

    /**
     * Check if principal can access a specific tenant.
     *
     * @param principal The principal
     * @param tenantId The tenant ID to check
     * @return true if principal can access the tenant
     */
    public boolean canAccessTenant(Principal principal, Long tenantId) {
        return getAccessibleTenants(principal).contains(tenantId);
    }

    /**
     * Check if principal is an anchor domain user (has global access).
     *
     * @param principal The principal
     * @return true if anchor domain user
     */
    public boolean isAnchorDomainUser(Principal principal) {
        if (principal.type != PrincipalType.USER || principal.userIdentity == null) {
            return false;
        }

        String domain = principal.userIdentity.emailDomain;
        return domain != null && anchorDomainRepo.existsByDomain(domain);
    }
}
