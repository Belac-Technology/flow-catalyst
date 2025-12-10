package tech.flowcatalyst.eventtype;

import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;
import java.util.List;

/**
 * Represents an event type in the FlowCatalyst platform.
 *
 * Event types define the structure and schema for events in the system.
 * Each event type has a globally unique code and can have multiple
 * schema versions for backwards compatibility.
 *
 * Code format: {APPLICATION}:{SUBDOMAIN}:{AGGREGATE}:{EVENT}
 * Example: inmotion:execution:trip:started
 */
@MongoEntity(collection = "event_types")
public record EventType(
    @BsonId
    String id,

    /**
     * Unique event type code (globally unique, not tenant-scoped).
     * Format: {app}:{subdomain}:{aggregate}:{event}
     */
    String code,

    /**
     * Human-friendly name for the event type.
     */
    String name,

    /**
     * Description of the event type.
     */
    String description,

    /**
     * Schema versions for this event type.
     */
    List<SpecVersion> specVersions,

    /**
     * Current status of the event type.
     */
    EventTypeStatus status,

    Instant createdAt,

    Instant updatedAt
) {

    /**
     * Find a spec version by version string.
     */
    public SpecVersion findSpecVersion(String version) {
        if (specVersions == null) return null;
        return specVersions.stream()
            .filter(sv -> sv.version().equals(version))
            .findFirst()
            .orElse(null);
    }

    /**
     * Check if all spec versions are in DEPRECATED status.
     */
    public boolean allVersionsDeprecated() {
        if (specVersions == null || specVersions.isEmpty()) {
            return true;
        }
        return specVersions.stream()
            .allMatch(sv -> sv.status() == SpecVersionStatus.DEPRECATED);
    }

    /**
     * Check if all spec versions are in FINALISING status (never finalized).
     */
    public boolean allVersionsFinalising() {
        if (specVersions == null || specVersions.isEmpty()) {
            return true;
        }
        return specVersions.stream()
            .allMatch(sv -> sv.status() == SpecVersionStatus.FINALISING);
    }

    /**
     * Check if a version string already exists.
     */
    public boolean hasVersion(String version) {
        return findSpecVersion(version) != null;
    }

    // ========================================================================
    // Wither methods for immutable updates
    // ========================================================================

    public EventType withName(String name) {
        return new EventType(id, code, name, description, specVersions, status, createdAt, Instant.now());
    }

    public EventType withDescription(String description) {
        return new EventType(id, code, name, description, specVersions, status, createdAt, Instant.now());
    }

    public EventType withNameAndDescription(String name, String description) {
        return new EventType(id, code, name, description, specVersions, status, createdAt, Instant.now());
    }

    public EventType withSpecVersions(List<SpecVersion> specVersions) {
        return new EventType(id, code, name, description, specVersions, status, createdAt, Instant.now());
    }

    public EventType withStatus(EventTypeStatus status) {
        return new EventType(id, code, name, description, specVersions, status, createdAt, Instant.now());
    }

    public EventType addSpecVersion(SpecVersion specVersion) {
        var newVersions = new java.util.ArrayList<>(specVersions != null ? specVersions : List.of());
        newVersions.add(specVersion);
        return new EventType(id, code, name, description, newVersions, status, createdAt, Instant.now());
    }
}
