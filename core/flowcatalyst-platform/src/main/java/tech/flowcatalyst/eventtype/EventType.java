package tech.flowcatalyst.eventtype;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;
import java.util.ArrayList;
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
public class EventType extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    /**
     * Unique event type code (globally unique, not tenant-scoped).
     * Format: {app}:{subdomain}:{aggregate}:{event}
     */
    public String code;

    /**
     * Human-friendly name for the event type.
     */
    public String name;

    /**
     * Description of the event type.
     */
    public String description;

    /**
     * Schema versions for this event type.
     */
    public List<SpecVersion> specVersions = new ArrayList<>();

    /**
     * Current status of the event type.
     */
    public EventTypeStatus status = EventTypeStatus.CURRENT;

    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

    public EventType() {
    }

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
}
