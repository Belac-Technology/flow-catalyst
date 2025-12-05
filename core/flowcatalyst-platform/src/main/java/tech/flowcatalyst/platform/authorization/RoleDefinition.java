package tech.flowcatalyst.platform.authorization;

import java.util.Set;

/**
 * Definition of a role in the FlowCatalyst system.
 *
 * Roles follow the structure: {subdomain}:{role-name}
 *
 * Examples:
 * - logistics:operator
 * - logistics:dispatcher
 * - logistics:warehouse-manager
 * - platform:tenant-admin
 *
 * Each role maps to a set of permission strings.
 * All parts must be lowercase alphanumeric with hyphens allowed.
 * This is code-first - roles are defined in code and synced to IDP at startup.
 *
 * Usage:
 * <pre>
 * @Role
 * public class MyRole {
 *     public static final RoleDefinition INSTANCE = RoleDefinition.make(
 *         "platform",
 *         "tenant-admin",
 *         Set.of(
 *             PlatformTenantUserCreatePermission.INSTANCE,
 *             PlatformTenantUserViewPermission.INSTANCE
 *         ),
 *         "Tenant administrator role"
 *     );
 * }
 * </pre>
 */
public interface RoleDefinition {

    String subdomain();           // Business domain (e.g., "logistics", "platform")
    String roleName();            // Role name within domain (e.g., "operator", "warehouse-manager")
    Set<PermissionRecord> permissions();    // Permissions this role grants
    String description();         // Human-readable description

    /**
     * Generate the string representation of this role.
     * Format: {subdomain}:{role-name}
     *
     * @return Role string (e.g., "logistics:dispatcher")
     */
    default String toRoleString() {
        return String.format("%s:%s", subdomain(), roleName());
    }

    /**
     * Get permission strings for this role.
     * Convenience method that converts PermissionRecord objects to strings.
     *
     * @return Set of permission strings (e.g., "platform:tenant:user:create")
     */
    default Set<String> permissionStrings() {
        return permissions().stream()
            .map(PermissionRecord::toPermissionString)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Static factory method to create a role from PermissionDefinition instances.
     * This is the preferred approach as it provides type safety and full metadata.
     *
     * @param subdomain Business domain
     * @param roleName Role name within domain
     * @param permissions Permission instances this role grants
     * @param description Human-readable description
     * @return Role instance
     */
    static RoleRecord make(String subdomain, String roleName, Set<PermissionDefinition> permissions, String description) {
        // Convert PermissionDefinitions to concrete PermissionRecord instances
        Set<PermissionRecord> permissionRecords = permissions.stream()
            .map(pd -> pd instanceof PermissionRecord pr ? pr :
                 PermissionDefinition.make(pd.subdomain(), pd.context(), pd.aggregate(), pd.action(), pd.description()))
            .collect(java.util.stream.Collectors.toSet());
        return new RoleRecord(subdomain, roleName, permissionRecords, description);
    }

    /**
     * Static factory method to create a role from permission strings.
     * Use this only when you don't have PermissionDefinition instances available (e.g., in tests with mocks).
     *
     * Note: This converts strings to Permission records.
     *
     * @param subdomain Business domain
     * @param roleName Role name within domain
     * @param permissionStrings Permission strings this role grants
     * @param description Human-readable description
     * @return Role instance
     */
    static RoleRecord makeFromStrings(String subdomain, String roleName, Set<String> permissionStrings, String description) {
        // Convert strings to PermissionRecord objects
        Set<PermissionRecord> permissions = permissionStrings.stream()
            .map(RoleDefinition::parsePermissionString)
            .collect(java.util.stream.Collectors.toSet());
        return new RoleRecord(subdomain, roleName, permissions, description);
    }

    /**
     * Parse a permission string into a PermissionRecord.
     * Format: subdomain:context:aggregate:action
     */
    private static PermissionRecord parsePermissionString(String permissionString) {
        String[] parts = permissionString.split(":");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid permission string format: " + permissionString);
        }
        return PermissionDefinition.make(parts[0], parts[1], parts[2], parts[3], "Permission created from string");
    }
}
