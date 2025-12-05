package tech.flowcatalyst.platform.eventtype;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tech.flowcatalyst.platform.shared.TsidGenerator;

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
@Entity
@Table(name = "event_types",
    uniqueConstraints = {
        @UniqueConstraint(name = "unique_event_type_code", columnNames = {"code"})
    },
    indexes = {
        @Index(name = "idx_event_type_code", columnList = "code"),
        @Index(name = "idx_event_type_status", columnList = "status")
    }
)
public class EventType extends PanacheEntityBase {

    @Id
    public Long id;

    /**
     * Unique event type code (globally unique, not tenant-scoped).
     * Format: {app}:{subdomain}:{aggregate}:{event}
     */
    @Column(nullable = false, length = 200)
    public String code;

    /**
     * Human-friendly name for the event type.
     */
    @Column(nullable = false, length = 100)
    public String name;

    /**
     * Description of the event type.
     */
    @Column(length = 255)
    public String description;

    /**
     * Schema versions for this event type.
     * Stored as JSON array.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_versions", columnDefinition = "jsonb")
    public List<SpecVersion> specVersions = new ArrayList<>();

    /**
     * Current status of the event type.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public EventTypeStatus status = EventTypeStatus.CURRENT;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

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
