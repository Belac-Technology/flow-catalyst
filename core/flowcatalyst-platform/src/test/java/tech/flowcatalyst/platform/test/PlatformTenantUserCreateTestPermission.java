package tech.flowcatalyst.platform.test;

import tech.flowcatalyst.platform.authorization.Permission;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;

@Permission
public class PlatformTenantUserCreateTestPermission {
    public static final PermissionDefinition INSTANCE = PermissionDefinition.make(
        "platform", "tenant", "user", "create", "Create users in tenant (test)"
    );
    private PlatformTenantUserCreateTestPermission() {}
}
