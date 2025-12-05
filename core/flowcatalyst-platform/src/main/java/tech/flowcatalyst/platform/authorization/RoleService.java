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
 * Roles can come from three sources:
 * - CODE: Defined in Java @Role classes (synced to auth_roles at startup)
 * - DATABASE: Created by administrators through the UI
 * - SDK: Registered by external applications via the SDK API
 *
 * Role validation checks both the auth_roles table (primary) and PermissionRegistry (fallback).
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

    @Inject
    AuthRoleRepository authRoleRepo;

    /**
     * Assign a role to a principal.
     *
     * Role must exist in either:
     * - auth_roles table (primary source - includes CODE, DATABASE, and SDK roles)
     * - PermissionRegistry (fallback for backwards compatibility)
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

        // Validate role exists in database or registry
        if (!isValidRole(roleName)) {
            throw new BadRequestException("Role not defined: " + roleName + ". " +
                "Role must exist in auth_roles table or be defined in code.");
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
     * Check if a role name is valid (exists in DB or registry).
     *
     * @param roleName Role name to validate
     * @return true if role exists
     */
    public boolean isValidRole(String roleName) {
        // Check database first (primary source after sync)
        if (authRoleRepo.existsByName(roleName)) {
            return true;
        }
        // Fallback to registry (for backwards compatibility during transition)
        return permissionRegistry.hasRole(roleName);
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

    /**
     * Get all available roles from the database.
     *
     * @return List of all AuthRole entities
     */
    public List<AuthRole> getAllRoles() {
        return authRoleRepo.listAll();
    }

    /**
     * Get all roles for a specific application.
     *
     * @param applicationCode Application code (e.g., "platform")
     * @return List of AuthRole entities for that application
     */
    public List<AuthRole> getRolesForApplication(String applicationCode) {
        return authRoleRepo.findByApplicationCode(applicationCode);
    }

    /**
     * Get a role by name from the database.
     *
     * @param roleName Full role name (e.g., "platform:tenant-admin")
     * @return AuthRole if found
     */
    public java.util.Optional<AuthRole> getRoleByName(String roleName) {
        return authRoleRepo.findByName(roleName);
    }
}
