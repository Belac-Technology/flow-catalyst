package tech.flowcatalyst.platform.test;

import tech.flowcatalyst.platform.authorization.Permission;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;

@Permission
public class PlatformTenantUserViewTestPermission {
    public static final PermissionDefinition INSTANCE = PermissionDefinition.make(
        "platform", "tenant", "user", "view", "View users in tenant (test)"
    );
    private PlatformTenantUserViewTestPermission() {}
}
