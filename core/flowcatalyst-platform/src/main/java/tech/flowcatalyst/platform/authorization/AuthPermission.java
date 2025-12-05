package tech.flowcatalyst.platform.authorization;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * Represents a permission definition stored in the database.
 *
 * This table is optional and primarily used by external applications
 * that register their permissions via the SDK. Platform permissions
 * remain code-only (defined in @Permission Java classes).
 *
 * Permission format: {app}:{context}:{aggregate}:{action}
 * Example: "myapp:orders:order:create"
 */
@Entity
@Table(name = "auth_permissions",
    indexes = {
        @Index(name = "idx_auth_permissions_application", columnList = "application_id"),
        @Index(name = "idx_auth_permissions_name", columnList = "name", unique = true)
    }
)
public class AuthPermission extends PanacheEntityBase {

    @Id
    public Long id;

    /**
     * The application this permission belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    public Application application;

    /**
     * Full permission name (e.g., "myapp:orders:order:create").
     */
    @Column(name = "name", unique = true, nullable = false, length = 150)
    public String name;

    /**
     * Human-readable display name.
     */
    @Column(name = "display_name", length = 100)
    public String displayName;

    /**
     * Description of what this permission grants.
     */
    @Column(name = "description", length = 500)
    public String description;

    /**
     * Source of this permission definition.
     * SDK = registered by external application
     * DATABASE = created by admin
     */
    @Column(name = "source", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public PermissionSource source = PermissionSource.SDK;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public AuthPermission() {
    }

    public AuthPermission(Application application, String name, String description, PermissionSource source) {
        this.application = application;
        this.name = name;
        this.description = description;
        this.source = source;
    }

    /**
     * Source of a permission definition.
     */
    public enum PermissionSource {
        /** Registered by external applications via the SDK API */
        SDK,
        /** Created by administrators */
        DATABASE
    }
}
