package tech.flowcatalyst.platform.test;

import tech.flowcatalyst.platform.authorization.Permission;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;

@Permission
public class PlatformTenantManageTestPermission {
    public static final PermissionDefinition INSTANCE = PermissionDefinition.make(
        "platform", "tenant", "tenant", "manage", "Manage tenant settings (test)"
    );
    private PlatformTenantManageTestPermission() {}
}
