package tech.flowcatalyst.platform.authorization;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import tech.flowcatalyst.platform.authorization.platform.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of all permission and role definitions.
 *
 * Permissions and roles are registered manually at startup.
 * Each @Permission class must have a public static final field named INSTANCE
 * of type PermissionDefinition.
 *
 * Each @Role class must have a public static final field named INSTANCE
 * of type RoleDefinition.
 *
 * The registry provides fast lookup of permissions and roles by their
 * string representation, and validates that all role permissions reference
 * valid permission definitions.
 *
 * This is the source of truth for all permissions and roles in the system.
 */
@ApplicationScoped
public class PermissionRegistry {

    // Permission string -> PermissionDefinition
    private final Map<String, PermissionDefinition> permissions = new ConcurrentHashMap<>();

    // Role string -> RoleDefinition
    private final Map<String, RoleDefinition> roles = new ConcurrentHashMap<>();

    /**
     * Initialize the registry at startup by manually registering permissions and roles.
     */
    void onStart(@Observes StartupEvent event) {
        Log.info("Initializing PermissionRegistry...");

        // Register all permissions and roles
        registerAll();

        Log.info("PermissionRegistry initialization complete. Registered " +
            permissions.size() + " permissions and " + roles.size() + " roles");
    }

    /**
     * Manually register all permissions and roles.
     * Add new permission and role classes here.
     *
     * IMPORTANT: Permissions must be registered before roles that depend on them.
     */
    private void registerAll() {
        // Register all platform permissions
        registerPermission(PlatformTenantViewPermission.INSTANCE);
        registerPermission(PlatformTenantCreatePermission.INSTANCE);
        registerPermission(PlatformTenantUserViewPermission.INSTANCE);
        registerPermission(PlatformTenantUserCreatePermission.INSTANCE);

        // Register all platform roles (must be after permissions)
        registerRole(PlatformTenantAdminRole.INSTANCE);
    }

    /**
     * Register a permission definition.
     * If a permission with the same string already exists, it is silently skipped.
     */
    public void registerPermission(PermissionDefinition permission) {
        String key = permission.toPermissionString();
        if (permissions.containsKey(key)) {
            Log.debug("Permission already registered, skipping: " + key);
            return;
        }
        permissions.put(key, permission);
        Log.debug("Registered permission: " + key);
    }

    /**
     * Register a role definition.
     * Validates that all role permissions reference existing permissions.
     * If a role with the same string already exists, it is silently skipped.
     */
    public void registerRole(RoleDefinition role) {
        String key = role.toRoleString();
        if (roles.containsKey(key)) {
            Log.debug("Role already registered, skipping: " + key);
            return;
        }

        // Validate that all role permissions reference existing permissions
        for (PermissionRecord permission : role.permissions()) {
            String permissionString = permission.toPermissionString();
            if (!permissions.containsKey(permissionString)) {
                throw new IllegalStateException(
                    "Role " + key + " references unknown permission: " + permissionString
                );
            }
        }

        roles.put(key, role);
        Log.debug("Registered role: " + key + " with " + role.permissions().size() + " permissions");
    }

    /**
     * Get a permission definition by its string representation.
     *
     * @param permissionString Permission string (e.g., "logistics:dispatch:order:create")
     * @return Optional containing the permission definition if found
     */
    public Optional<PermissionDefinition> getPermission(String permissionString) {
        return Optional.ofNullable(permissions.get(permissionString));
    }

    /**
     * Get a role definition by its string representation.
     *
     * @param roleString Role string (e.g., "logistics:dispatcher")
     * @return Optional containing the role definition if found
     */
    public Optional<RoleDefinition> getRole(String roleString) {
        return Optional.ofNullable(roles.get(roleString));
    }

    /**
     * Check if a permission exists.
     *
     * @param permissionString Permission string
     * @return true if the permission is registered
     */
    public boolean hasPermission(String permissionString) {
        return permissions.containsKey(permissionString);
    }

    /**
     * Check if a role exists.
     *
     * @param roleString Role string
     * @return true if the role is registered
     */
    public boolean hasRole(String roleString) {
        return roles.containsKey(roleString);
    }

    /**
     * Get all registered permissions.
     *
     * @return Unmodifiable collection of all permission definitions
     */
    public Collection<PermissionDefinition> getAllPermissions() {
        return Collections.unmodifiableCollection(permissions.values());
    }

    /**
     * Get all registered roles.
     *
     * @return Unmodifiable collection of all role definitions
     */
    public Collection<RoleDefinition> getAllRoles() {
        return Collections.unmodifiableCollection(roles.values());
    }

    /**
     * Get all permissions granted by a role.
     * This provides access to full permission metadata (subdomain, context, aggregate, action, description).
     *
     * @param roleString Role string
     * @return Set of permissions, or empty set if role not found
     */
    public Set<PermissionRecord> getPermissionsForRole(String roleString) {
        RoleDefinition role = roles.get(roleString);
        return role != null ? role.permissions() : Collections.emptySet();
    }

    /**
     * Get all permission strings granted by multiple roles.
     *
     * @param roleStrings Collection of role strings
     * @return Set of unique permission strings from all roles
     */
    public Set<String> getPermissionsForRoles(Collection<String> roleStrings) {
        Set<String> allPermissions = new HashSet<>();
        for (String roleString : roleStrings) {
            RoleDefinition role = roles.get(roleString);
            if (role != null) {
                allPermissions.addAll(role.permissionStrings());
            }
        }
        return allPermissions;
    }
}
