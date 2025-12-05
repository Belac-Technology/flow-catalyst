package tech.flowcatalyst.platform.authorization.platform;

import tech.flowcatalyst.platform.authorization.Permission;
import tech.flowcatalyst.platform.authorization.PermissionDefinition;

/**
 * Messaging and event management permissions.
 * Controls access to event types, subscriptions, and dispatch jobs.
 */
@Permission
public class PlatformMessagingPermissions {

    // ========================================================================
    // Event Type Management
    // ========================================================================

    public static final PermissionDefinition EVENT_TYPE_VIEW = PermissionDefinition.make(
        "platform", "messaging", "event-type", "view",
        "View event type definitions"
    );

    public static final PermissionDefinition EVENT_TYPE_CREATE = PermissionDefinition.make(
        "platform", "messaging", "event-type", "create",
        "Create new event types"
    );

    public static final PermissionDefinition EVENT_TYPE_UPDATE = PermissionDefinition.make(
        "platform", "messaging", "event-type", "update",
        "Update event type definitions"
    );

    public static final PermissionDefinition EVENT_TYPE_DELETE = PermissionDefinition.make(
        "platform", "messaging", "event-type", "delete",
        "Delete event types"
    );

    // ========================================================================
    // Subscription Management
    // ========================================================================

    public static final PermissionDefinition SUBSCRIPTION_VIEW = PermissionDefinition.make(
        "platform", "messaging", "subscription", "view",
        "View webhook subscriptions"
    );

    public static final PermissionDefinition SUBSCRIPTION_CREATE = PermissionDefinition.make(
        "platform", "messaging", "subscription", "create",
        "Create webhook subscriptions"
    );

    public static final PermissionDefinition SUBSCRIPTION_UPDATE = PermissionDefinition.make(
        "platform", "messaging", "subscription", "update",
        "Update webhook subscriptions"
    );

    public static final PermissionDefinition SUBSCRIPTION_DELETE = PermissionDefinition.make(
        "platform", "messaging", "subscription", "delete",
        "Delete webhook subscriptions"
    );

    // ========================================================================
    // Dispatch Job Management
    // ========================================================================

    public static final PermissionDefinition DISPATCH_JOB_VIEW = PermissionDefinition.make(
        "platform", "messaging", "dispatch-job", "view",
        "View dispatch jobs and delivery status"
    );

    public static final PermissionDefinition DISPATCH_JOB_RETRY = PermissionDefinition.make(
        "platform", "messaging", "dispatch-job", "retry",
        "Retry failed dispatch jobs"
    );

    private PlatformMessagingPermissions() {}
}
