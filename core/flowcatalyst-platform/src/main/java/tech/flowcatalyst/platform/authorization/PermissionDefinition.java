package tech.flowcatalyst.platform.authorization;

/**
 * Definition of a permission in the FlowCatalyst system.
 *
 * Permissions follow the structure: {subdomain}:{context}:{aggregate}:{action}
 *
 * Examples:
 * - logistics:dispatch:order:create
 * - logistics:warehouse:inventory:view
 * - platform:tenant:tenant:manage
 * - platform:billing:invoice:view
 *
 * All parts must be lowercase alphanumeric with hyphens allowed.
 * This is code-first - permissions are defined in code and synced to IDP at startup.
 *
 * Usage:
 * <pre>
 * @tech.flowcatalyst.platform.authorization.Permission
 * public class MyPermissionClass {
 *     public static final PermissionDefinition INSTANCE = PermissionDefinition.make(
 *         "platform", "tenant", "user", "create", "Create users in tenant"
 *     );
 * }
 * </pre>
 */
public interface PermissionDefinition {

    String subdomain();    // Business domain (e.g., "logistics", "platform")
    String context();      // Bounded context within domain (e.g., "dispatch", "warehouse")
    String aggregate();    // Resource/entity (e.g., "order", "inventory")
    String action();       // Operation (e.g., "create", "view", "update", "delete")
    String description();  // Human-readable description

    /**
     * Generate the string representation of this permission.
     * Format: {subdomain}:{context}:{aggregate}:{action}
     *
     * @return Permission string (e.g., "logistics:dispatch:order:create")
     */
    default String toPermissionString() {
        return String.format("%s:%s:%s:%s", subdomain(), context(), aggregate(), action());
    }

    /**
     * Static factory method to create a permission.
     *
     * @param subdomain Business domain
     * @param context Bounded context
     * @param aggregate Resource/entity
     * @param action Operation
     * @param description Human-readable description
     * @return Permission instance
     */
    static PermissionRecord make(String subdomain, String context, String aggregate,
                          String action, String description) {
        return new PermissionRecord(subdomain, context, aggregate, action, description);
    }
}
