package tech.flowcatalyst.platform.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.tenant.TenantAccessGrantRepository;
import tech.flowcatalyst.platform.tenant.TenantRepository;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service for tenant CRUD operations and access management.
 */
@ApplicationScoped
public class TenantService {

    @Inject
    TenantRepository tenantRepo;

    @Inject
    TenantAccessGrantRepository grantRepo;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    TenantAccessService tenantAccessService;

    /**
     * Create a new tenant.
     *
     * @param name Tenant display name
     * @param identifier Unique tenant identifier/slug (e.g., "acme-corp")
     * @return Created tenant
     * @throws BadRequestException if identifier already exists
     */
    @Transactional
    public Tenant createTenant(String name, String identifier) {
        // Validate identifier uniqueness
        if (tenantRepo.findByIdentifier(identifier).isPresent()) {
            throw new BadRequestException("Tenant identifier already exists: " + identifier);
        }

        // Create tenant
        Tenant tenant = new Tenant();
        tenant.id = TsidGenerator.generate();
        tenant.name = name;
        tenant.identifier = identifier;
        tenant.status = TenantStatus.ACTIVE;

        tenantRepo.persist(tenant);
        return tenant;
    }

    /**
     * Update tenant name.
     *
     * @param tenantId Tenant ID
     * @param name New tenant name
     * @return Updated tenant
     * @throws NotFoundException if tenant not found
     */
    @Transactional
    public Tenant updateTenant(Long tenantId, String name) {
        Tenant tenant = tenantRepo.findByIdOptional(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found"));

        tenant.name = name;
        return tenant;
    }

    /**
     * Change tenant status.
     *
     * @param tenantId Tenant ID
     * @param status New status
     * @param reason Status reason (e.g., "ACCOUNT_NOT_PAID")
     * @param note Optional note to add to audit trail
     * @param changedBy Principal ID of who made the change
     * @throws NotFoundException if tenant not found
     */
    @Transactional
    public void changeTenantStatus(Long tenantId, TenantStatus status, String reason, String note, String changedBy) {
        Tenant tenant = tenantRepo.findByIdOptional(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found"));

        tenant.changeStatus(status, reason, note, changedBy);
    }

    /**
     * Deactivate a tenant (soft delete).
     * Deactivated tenants cannot be accessed.
     *
     * @param tenantId Tenant ID
     * @param reason Reason for deactivation
     * @param changedBy Who deactivated the tenant
     * @throws NotFoundException if tenant not found
     */
    @Transactional
    public void deactivateTenant(Long tenantId, String reason, String changedBy) {
        changeTenantStatus(tenantId, TenantStatus.INACTIVE, reason,
            "Tenant deactivated: " + reason, changedBy);
    }

    /**
     * Suspend a tenant.
     *
     * @param tenantId Tenant ID
     * @param reason Reason for suspension
     * @param changedBy Who suspended the tenant
     * @throws NotFoundException if tenant not found
     */
    @Transactional
    public void suspendTenant(Long tenantId, String reason, String changedBy) {
        changeTenantStatus(tenantId, TenantStatus.SUSPENDED, reason,
            "Tenant suspended: " + reason, changedBy);
    }

    /**
     * Activate a tenant (un-suspend or un-deactivate).
     *
     * @param tenantId Tenant ID
     * @param changedBy Who activated the tenant
     * @throws NotFoundException if tenant not found
     */
    @Transactional
    public void activateTenant(Long tenantId, String changedBy) {
        changeTenantStatus(tenantId, TenantStatus.ACTIVE, null,
            "Tenant activated", changedBy);
    }

    /**
     * Grant a principal access to a tenant.
     * Used for partners who need access to multiple customer tenants.
     *
     * @param principalId Principal ID
     * @param tenantId Tenant ID
     * @return Created grant
     * @throws NotFoundException if principal or tenant not found
     * @throws BadRequestException if grant already exists or if principal already belongs to tenant
     */
    @Transactional
    public TenantAccessGrant grantTenantAccess(Long principalId, Long tenantId) {
        // Validate principal exists
        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("Principal not found"));

        // Validate tenant exists
        Tenant tenant = tenantRepo.findByIdOptional(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found"));

        // Check if principal already belongs to this tenant as home tenant
        if (principal.tenantId != null && principal.tenantId.equals(tenantId)) {
            throw new BadRequestException("Principal already belongs to this tenant as home tenant");
        }

        // Check if grant already exists
        long existingGrants = grantRepo.count("principalId = ?1 AND tenantId = ?2", principalId, tenantId);
        if (existingGrants > 0) {
            throw new BadRequestException("Tenant access grant already exists");
        }

        // Create grant
        TenantAccessGrant grant = new TenantAccessGrant();
        grant.id = TsidGenerator.generate();
        grant.principalId = principalId;
        grant.tenantId = tenantId;

        grantRepo.persist(grant);
        return grant;
    }

    /**
     * Revoke a principal's access to a tenant.
     *
     * @param principalId Principal ID
     * @param tenantId Tenant ID
     * @throws NotFoundException if grant not found
     */
    @Transactional
    public void revokeTenantAccess(Long principalId, Long tenantId) {
        long deleted = grantRepo.delete("principalId = ?1 AND tenantId = ?2", principalId, tenantId);
        if (deleted == 0) {
            throw new NotFoundException("Tenant access grant not found");
        }
    }

    /**
     * Get all tenants accessible by a principal.
     * Uses TenantAccessService to calculate accessible tenants.
     *
     * @param principalId Principal ID
     * @return Set of accessible tenant IDs
     * @throws NotFoundException if principal not found
     */
    public Set<Long> getAccessibleTenants(Long principalId) {
        Principal principal = principalRepo.findByIdOptional(principalId)
            .orElseThrow(() -> new NotFoundException("Principal not found"));

        return tenantAccessService.getAccessibleTenants(principal);
    }

    /**
     * Get all principals who have access to a specific tenant.
     *
     * @param tenantId Tenant ID
     * @return List of principals
     */
    public List<Principal> getPrincipalsWithAccess(Long tenantId) {
        // Get all grants for this tenant
        List<TenantAccessGrant> grants = grantRepo.findByTenantId(tenantId);

        // Get principals for those grants
        List<Long> principalIds = grants.stream()
            .map(g -> g.principalId)
            .toList();

        // Also include users who have this as their home tenant
        List<Principal> principals = principalRepo.find("tenantId", tenantId).list();

        // Add granted principals
        if (!principalIds.isEmpty()) {
            List<Principal> grantedPrincipals = principalRepo.find("id in ?1", principalIds).list();
            principals.addAll(grantedPrincipals);
        }

        return principals;
    }

    /**
     * Find tenant by ID.
     *
     * @param tenantId Tenant ID
     * @return Optional containing the tenant if found
     */
    public Optional<Tenant> findById(Long tenantId) {
        return tenantRepo.findByIdOptional(tenantId);
    }

    /**
     * Find tenant by identifier.
     *
     * @param identifier Tenant identifier/slug
     * @return Optional containing the tenant if found
     */
    public Optional<Tenant> findByIdentifier(String identifier) {
        return tenantRepo.findByIdentifier(identifier);
    }

    /**
     * Find all active tenants.
     *
     * @return List of active tenants
     */
    public List<Tenant> findAllActive() {
        return tenantRepo.findAllActive();
    }

    /**
     * Find all tenants (regardless of status).
     *
     * @return List of all tenants
     */
    public List<Tenant> findAll() {
        return tenantRepo.listAll();
    }

    /**
     * Add a note to a tenant's audit trail.
     *
     * @param tenantId Tenant ID
     * @param category Note category (e.g., "SUPPORT", "BILLING")
     * @param text Note text
     * @param addedBy Who added the note
     * @throws NotFoundException if tenant not found
     */
    @Transactional
    public void addNote(Long tenantId, String category, String text, String addedBy) {
        Tenant tenant = tenantRepo.findByIdOptional(tenantId)
            .orElseThrow(() -> new NotFoundException("Tenant not found"));

        tenant.addNote(category, text, addedBy);
    }
}
