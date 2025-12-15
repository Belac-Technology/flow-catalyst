package tech.flowcatalyst.subscription;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;
import tech.flowcatalyst.dispatch.DispatchMode;

import java.time.Instant;
import java.util.List;

/**
 * A subscription defines how events are dispatched to a target endpoint.
 *
 * Subscriptions bind event types to a target URL and configure dispatch behavior
 * including rate limiting (via dispatch pool), ordering guarantees, and timeouts.
 *
 * Code uniqueness is enforced per clientId (null = anchor-level).
 */
@MongoEntity(collection = "subscriptions")
public record Subscription(
    @BsonId
    String id,

    /** Unique code within client scope (or anchor-level if clientId is null) */
    String code,

    /** Display name */
    String name,

    /** Optional description */
    String description,

    /** Client this subscription belongs to (nullable - null means anchor-level subscription) */
    String clientId,

    /** Denormalized client identifier for queries (nullable) */
    String clientIdentifier,

    /** List of event types this subscription listens to */
    List<EventTypeBinding> eventTypes,

    /** Target URL for dispatching (HTTP endpoint) */
    String target,

    /** Queue name for message routing */
    String queue,

    /** Custom configuration entries for additional instructions */
    List<ConfigEntry> customConfig,

    /** How this subscription was created (API or UI) */
    SubscriptionSource source,

    /** Current status */
    SubscriptionStatus status,

    /** Maximum age in seconds for resulting dispatch jobs before they expire */
    int maxAgeSeconds,

    /** Dispatch pool ID for rate limiting */
    String dispatchPoolId,

    /** Denormalized dispatch pool code for queries */
    String dispatchPoolCode,

    /** Delay in seconds before first dispatch attempt */
    int delaySeconds,

    /** Sequence number for ordering dispatch jobs (default 99) */
    int sequence,

    /** Processing mode for message group ordering */
    DispatchMode mode,

    /** Timeout in seconds for dispatch target to respond */
    int timeoutSeconds,

    /**
     * Controls payload delivery format for dispatch jobs created by this subscription.
     *
     * <p>When {@code dataOnly = true} (default):</p>
     * <ul>
     *   <li>Only the raw event payload is sent to the target</li>
     *   <li>Standard FlowCatalyst headers are included (X-FlowCatalyst-ID, etc.)</li>
     *   <li>No JSON envelope wrapping</li>
     * </ul>
     *
     * <p>When {@code dataOnly = false}:</p>
     * <ul>
     *   <li>Event data is wrapped in a JSON envelope with metadata</li>
     *   <li>Envelope includes: id, kind, code, subject, eventId, timestamp, data</li>
     *   <li>Useful when the target needs access to dispatch metadata in the body</li>
     * </ul>
     */
    boolean dataOnly,

    Instant createdAt,
    Instant updatedAt
) {
    // ========================================================================
    // Default values
    // ========================================================================

    public static final int DEFAULT_MAX_AGE_SECONDS = 86400; // 24 hours
    public static final int DEFAULT_DELAY_SECONDS = 0;
    public static final int DEFAULT_SEQUENCE = 99;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final boolean DEFAULT_DATA_ONLY = true;

    // ========================================================================
    // Domain logic
    // ========================================================================

    /**
     * Check if this is an anchor-level subscription (not client-specific).
     */
    public boolean isAnchorLevel() {
        return clientId == null;
    }

    /**
     * Check if this subscription is active.
     */
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE;
    }

    /**
     * Check if this subscription is paused.
     */
    public boolean isPaused() {
        return status == SubscriptionStatus.PAUSED;
    }

    /**
     * Check if this subscription uses immediate mode (no message group ordering).
     */
    public boolean isImmediateMode() {
        return mode == DispatchMode.IMMEDIATE;
    }

    /**
     * Check if this subscription blocks on error within message groups.
     */
    public boolean blocksOnError() {
        return mode == DispatchMode.BLOCK_ON_ERROR;
    }
}
