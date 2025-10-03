package tech.flowcatalyst.platform.authorization.platform;

import tech.flowcatalyst.platform.authorization.Role;
import tech.flowcatalyst.platform.authorization.RoleDefinition;

import java.util.Set;

/**
 * Tenant administrator role - can manage users within their tenant.
 */
@Role
public class PlatformTenantAdminRole {
    public static final RoleDefinition INSTANCE = RoleDefinition.make(
        "platform",
        "tenant-admin",
        Set.of(
            PlatformTenantViewPermission.INSTANCE,
            PlatformTenantUserCreatePermission.INSTANCE,
            PlatformTenantUserViewPermission.INSTANCE
        ),
        "Tenant administrator - can manage users in tenant"
    );

    private PlatformTenantAdminRole() {}
}
