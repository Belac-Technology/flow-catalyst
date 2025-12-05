package tech.flowcatalyst.platform.authorization.platform;

import tech.flowcatalyst.platform.authorization.Role;
import tech.flowcatalyst.platform.authorization.RoleDefinition;

import java.util.Set;

/**
 * Platform super administrator role - full access to everything.
 *
 * This role grants all permissions and bypasses most access checks.
 * Should only be assigned to platform operators.
 */
@Role
public class PlatformSuperAdminRole {
    public static final String ROLE_NAME = "platform:super-admin";

    public static final RoleDefinition INSTANCE = RoleDefinition.make(
        "platform",
        "super-admin",
        Set.of(
            // Super admin has all permissions - represented by wildcard in practice
            // Individual permissions listed for reference/documentation
            PlatformIamPermissions.USER_VIEW,
            PlatformIamPermissions.USER_CREATE,
            PlatformIamPermissions.USER_UPDATE,
            PlatformIamPermissions.USER_DELETE,
            PlatformIamPermissions.ROLE_VIEW,
            PlatformIamPermissions.ROLE_CREATE,
            PlatformIamPermissions.ROLE_UPDATE,
            PlatformIamPermissions.ROLE_DELETE,
            PlatformIamPermissions.PERMISSION_VIEW,
            PlatformAdminPermissions.CLIENT_VIEW,
            PlatformAdminPermissions.CLIENT_CREATE,
            PlatformAdminPermissions.CLIENT_UPDATE,
            PlatformAdminPermissions.CLIENT_DELETE,
            PlatformAdminPermissions.APPLICATION_VIEW,
            PlatformAdminPermissions.APPLICATION_CREATE,
            PlatformAdminPermissions.APPLICATION_UPDATE,
            PlatformAdminPermissions.APPLICATION_DELETE,
            PlatformMessagingPermissions.EVENT_TYPE_VIEW,
            PlatformMessagingPermissions.EVENT_TYPE_CREATE,
            PlatformMessagingPermissions.EVENT_TYPE_UPDATE,
            PlatformMessagingPermissions.EVENT_TYPE_DELETE,
            PlatformMessagingPermissions.SUBSCRIPTION_VIEW,
            PlatformMessagingPermissions.SUBSCRIPTION_CREATE,
            PlatformMessagingPermissions.SUBSCRIPTION_UPDATE,
            PlatformMessagingPermissions.SUBSCRIPTION_DELETE,
            PlatformMessagingPermissions.DISPATCH_JOB_VIEW,
            PlatformMessagingPermissions.DISPATCH_JOB_RETRY
        ),
        "Platform super administrator - full access to everything"
    );

    private PlatformSuperAdminRole() {}
}
