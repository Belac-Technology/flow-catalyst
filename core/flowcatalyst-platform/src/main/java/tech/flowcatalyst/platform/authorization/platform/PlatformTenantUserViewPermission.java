package tech.flowcatalyst.platform.authorization.platform;

import tech.flowcatalyst.platform.authorization.Permission;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;

/**
 * Permission to view users within a tenant.
 */
@Permission
public class PlatformTenantUserViewPermission {
    public static final PermissionDefinition INSTANCE = PermissionDefinition.make(
        "platform",
        "tenant",
        "user",
        "view",
        "View users in tenant"
    );

    private PlatformTenantUserViewPermission() {}
}
