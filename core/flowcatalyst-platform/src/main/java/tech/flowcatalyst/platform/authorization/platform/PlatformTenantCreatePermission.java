package tech.flowcatalyst.platform.authorization.platform;

import tech.flowcatalyst.platform.authorization.Permission;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;

/**
 * Permission to create tenants in the platform.
 */
@Permission
public class PlatformTenantCreatePermission {
    public static final PermissionDefinition INSTANCE = PermissionDefinition.make(
        "platform",
        "tenant",
        "tenant",
        "create",
        "Create new tenants in the platform"
    );

    private PlatformTenantCreatePermission() {}
}
