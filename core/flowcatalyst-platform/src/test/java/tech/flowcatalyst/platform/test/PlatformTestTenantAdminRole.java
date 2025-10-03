package tech.flowcatalyst.platform.test;

import tech.flowcatalyst.platform.authorization.Role;
import tech.flowcatalyst.platform.authorization.RoleDefinition;
import java.util.Set;

@Role
public class PlatformTestTenantAdminRole {
    public static final RoleDefinition INSTANCE = RoleDefinition.make(
        "platform",
        "test-tenant-admin",
        Set.of(
            PlatformTenantUserCreateTestPermission.INSTANCE,
            PlatformTenantUserViewTestPermission.INSTANCE,
            PlatformTenantManageTestPermission.INSTANCE
        ),
        "Test tenant admin role"
    );
    private PlatformTestTenantAdminRole() {}
}
