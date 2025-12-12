package tech.flowcatalyst.subscription.operations.createsubscription;

import tech.flowcatalyst.subscription.EventTypeBinding;
import tech.flowcatalyst.subscription.SubscriptionMode;
import tech.flowcatalyst.subscription.SubscriptionSource;

import java.util.List;
import java.util.Map;

/**
 * Command to create a new subscription.
 *
 * @param code Unique code within client scope
 * @param name Display name
 * @param description Optional description
 * @param clientId Client ID (nullable - null for anchor-level)
 * @param eventTypes List of event type bindings
 * @param target Target URL for dispatching
 * @param queue Queue name
 * @param customConfig Custom configuration JSON
 * @param source How this subscription was created
 * @param maxAgeSeconds Maximum age for dispatch jobs
 * @param dispatchPoolId Dispatch pool ID
 * @param delaySeconds Delay before first attempt
 * @param sequence Sequence number for ordering
 * @param mode Processing mode
 * @param timeoutSeconds Timeout for dispatch target
 */
public record CreateSubscriptionCommand(
    String code,
    String name,
    String description,
    String clientId,
    List<EventTypeBinding> eventTypes,
    String target,
    String queue,
    Map<String, Object> customConfig,
    SubscriptionSource source,
    Integer maxAgeSeconds,
    String dispatchPoolId,
    Integer delaySeconds,
    Integer sequence,
    SubscriptionMode mode,
    Integer timeoutSeconds
) {}
