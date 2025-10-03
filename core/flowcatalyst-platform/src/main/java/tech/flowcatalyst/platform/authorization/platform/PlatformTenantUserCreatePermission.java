package tech.flowcatalyst.platform.authorization.platform;

import tech.flowcatalyst.platform.authorization.Permission;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;

/**
 * Permission to create users within a tenant.
 */
@Permission
public class PlatformTenantUserCreatePermission {
    public static final PermissionDefinition INSTANCE = PermissionDefinition.make(
        "platform",
        "tenant",
        "user",
        "create",
        "Create new users in tenant"
    );

    private PlatformTenantUserCreatePermission() {}
}
