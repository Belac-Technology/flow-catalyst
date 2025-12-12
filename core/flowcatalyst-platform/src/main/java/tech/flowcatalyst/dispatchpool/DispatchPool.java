package tech.flowcatalyst.dispatchpool;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;

/**
 * A dispatch pool controls the rate at which dispatch jobs can be processed.
 *
 * Pools define rate limits (per minute) and concurrency limits for message dispatching.
 * Each pool belongs to an application and optionally to a client:
 * - Client-specific pools: clientId is set, pool is scoped to that client
 * - Anchor-level pools: clientId is null, pool is for non-client-scoped dispatch jobs
 *
 * Code uniqueness is enforced per clientId + applicationId combination.
 */
@MongoEntity(collection = "dispatch_pools")
public record DispatchPool(
    @BsonId
    String id,

    String code,
    String name,
    String description,

    /** Maximum dispatches per minute */
    int rateLimit,

    /** Maximum concurrent dispatches (must be >= 1) */
    int concurrency,

    /** Application this pool belongs to (required) */
    String applicationId,

    /** Denormalized application code for queries */
    String applicationCode,

    /** Client this pool belongs to (nullable - null means anchor-level pool) */
    String clientId,

    /** Denormalized client identifier for queries (nullable) */
    String clientIdentifier,

    DispatchPoolStatus status,

    Instant createdAt,
    Instant updatedAt
) {
    // ========================================================================
    // Wither methods for immutable updates
    // ========================================================================

    public DispatchPool withName(String name) {
        return new DispatchPool(id, code, name, description, rateLimit, concurrency,
            applicationId, applicationCode, clientId, clientIdentifier,
            status, createdAt, Instant.now());
    }

    public DispatchPool withDescription(String description) {
        return new DispatchPool(id, code, name, description, rateLimit, concurrency,
            applicationId, applicationCode, clientId, clientIdentifier,
            status, createdAt, Instant.now());
    }

    public DispatchPool withRateLimit(int rateLimit) {
        return new DispatchPool(id, code, name, description, rateLimit, concurrency,
            applicationId, applicationCode, clientId, clientIdentifier,
            status, createdAt, Instant.now());
    }

    public DispatchPool withConcurrency(int concurrency) {
        return new DispatchPool(id, code, name, description, rateLimit, concurrency,
            applicationId, applicationCode, clientId, clientIdentifier,
            status, createdAt, Instant.now());
    }

    public DispatchPool withStatus(DispatchPoolStatus status) {
        return new DispatchPool(id, code, name, description, rateLimit, concurrency,
            applicationId, applicationCode, clientId, clientIdentifier,
            status, createdAt, Instant.now());
    }

    // ========================================================================
    // Domain logic
    // ========================================================================

    /**
     * Check if this is an anchor-level pool (not client-specific).
     */
    public boolean isAnchorLevel() {
        return clientId == null;
    }

    /**
     * Check if this pool is active and can process jobs.
     */
    public boolean isActive() {
        return status == DispatchPoolStatus.ACTIVE;
    }

    /**
     * Check if this pool is archived.
     */
    public boolean isArchived() {
        return status == DispatchPoolStatus.ARCHIVED;
    }
}
