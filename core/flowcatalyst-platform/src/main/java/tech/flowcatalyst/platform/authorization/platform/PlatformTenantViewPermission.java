package tech.flowcatalyst.platform.authorization.platform;

import tech.flowcatalyst.platform.authorization.Permission;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;

/**
 * Permission to view tenant details.
 */
@Permission
public class PlatformTenantViewPermission {
    public static final PermissionDefinition INSTANCE = PermissionDefinition.make(
        "platform",
        "tenant",
        "tenant",
        "view",
        "View tenant details and settings"
    );

    private PlatformTenantViewPermission() {}
}
