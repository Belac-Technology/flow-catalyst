package tech.flowcatalyst.platform.authorization;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for role assignment to principals.
 *
 * Roles are now code-first and defined in PermissionRegistry via PermissionFactory/RoleFactory implementations.
 * This service only handles assigning/removing role names from principals, not creating/updating role definitions.
 *
 * Role format: {subdomain}:{role-name}
 * Example: "platform:tenant-admin", "logistics:dispatcher"
 */
@ApplicationScoped
public class RoleService {

    @Inject
    PrincipalRoleRepository principalRoleRepo;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    PermissionRegistry permissionRegistry;

    /**
     * Assign a role to a principal.
     *
     * NOTE: Role must be defined in PermissionRegistry (via RoleFactory implementations).
     * This method only creates the assignment, it does not create role definitions.
     *
     * @param principalId Principal ID
     * @param roleName Role name string (e.g., "platform:tenant-admin")
     * @param assignmentSource How the role was assigned ("MANUAL", "IDP_SYNC", etc.)
     * @return Created principal role assignment
     * @throws NotFoundException if principal not found
     * @throws BadRequestException if assignment already exists or role not defined
     */
    @Transactional
    public PrincipalRole assignRole(Long principalId, String roleName, String assignmentSource) {
        // Validate principal exists
        if (!principalRepo.findByIdOptional(principalId).isPresent()) {
            throw new NotFoundException("Principal not found: " + principalId);
        }

        // Validate role is defined in PermissionRegistry
        if (!permissionRegistry.hasRole(roleName)) {
            throw new BadRequestException("Role not defined in PermissionRegistry: " + roleName + ". " +
                "Roles must be defined in code via RoleFactory implementations.");
        }

        // Check if assignment already exists
        long existing = principalRoleRepo.count("principalId = ?1 AND roleName = ?2", principalId, roleName);
        if (existing > 0) {
            throw new BadRequestException("Role already assigned to principal: " + roleName);
        }

        // Create assignment
        PrincipalRole principalRole = new PrincipalRole();
        principalRole.id = TsidGenerator.generate();
        principalRole.principalId = principalId;
        principalRole.roleName = roleName;
        principalRole.assignmentSource = assignmentSource != null ? assignmentSource : "MANUAL";

        principalRoleRepo.persist(principalRole);
        return principalRole;
    }

    /**
     * Remove a role from a principal.
     *
     * @param principalId Principal ID
     * @param roleName Role name string (e.g., "platform:tenant-admin")
     * @throws NotFoundException if assignment not found
     */
    @Transactional
    public void removeRole(Long principalId, String roleName) {
        long deleted = principalRoleRepo.delete("principalId = ?1 AND roleName = ?2", principalId, roleName);
        if (deleted == 0) {
            throw new NotFoundException("Role assignment not found: " + roleName);
        }
    }

    /**
     * Remove all roles from a principal that have a specific assignment source.
     * Used for IDP sync to remove old IDP-assigned roles before adding new ones.
     *
     * @param principalId Principal ID
     * @param assignmentSource Assignment source to remove (e.g., "IDP_SYNC")
     * @return Number of roles removed
     */
    @Transactional
    public long removeRolesBySource(Long principalId, String assignmentSource) {
        return principalRoleRepo.delete("principalId = ?1 AND assignmentSource = ?2",
            principalId, assignmentSource);
    }

    /**
     * Find all role names assigned to a principal.
     *
     * @param principalId Principal ID
     * @return Set of role name strings (e.g., "platform:tenant-admin")
     */
    public Set<String> findRoleNamesByPrincipal(Long principalId) {
        return principalRoleRepo.findByPrincipalId(principalId).stream()
            .map(pr -> pr.roleName)
            .collect(Collectors.toSet());
    }

    /**
     * Find all role definitions assigned to a principal.
     * Includes full role metadata from PermissionRegistry.
     *
     * @param principalId Principal ID
     * @return Set of role definitions
     */
    public Set<RoleDefinition> findRoleDefinitionsByPrincipal(Long principalId) {
        return findRoleNamesByPrincipal(principalId).stream()
            .map(roleName -> permissionRegistry.getRole(roleName))
            .filter(opt -> opt.isPresent())
            .map(opt -> opt.get())
            .collect(Collectors.toSet());
    }

    /**
     * Find all principal role assignments for a principal.
     *
     * @param principalId Principal ID
     * @return List of principal role assignments
     */
    public List<PrincipalRole> findAssignmentsByPrincipal(Long principalId) {
        return principalRoleRepo.findByPrincipalId(principalId);
    }

    /**
     * Check if a principal has a specific role.
     *
     * @param principalId Principal ID
     * @param roleName Role name string
     * @return true if principal has the role
     */
    public boolean hasRole(Long principalId, String roleName) {
        return findRoleNamesByPrincipal(principalId).contains(roleName);
    }

    /**
     * Get all permission strings granted to a principal via their roles.
     *
     * @param principalId Principal ID
     * @return Set of permission strings
     */
    public Set<String> getPermissionsForPrincipal(Long principalId) {
        Set<String> roleNames = findRoleNamesByPrincipal(principalId);
        return permissionRegistry.getPermissionsForRoles(roleNames);
    }
}
