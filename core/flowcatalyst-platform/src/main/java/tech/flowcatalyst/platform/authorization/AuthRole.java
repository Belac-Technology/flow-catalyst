package tech.flowcatalyst.platform.authorization;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a role definition stored in the database.
 *
 * Roles can come from three sources:
 * - CODE: Defined in Java @Role classes, synced to DB at startup
 * - DATABASE: Created by administrators through the UI
 * - SDK: Registered by external applications via the SDK API
 *
 * The role name is prefixed with the application code (e.g., "platform:tenant-admin").
 * SDK roles are auto-prefixed when registered.
 */
@Entity
@Table(name = "auth_roles",
    indexes = {
        @Index(name = "idx_auth_roles_application", columnList = "application_id"),
        @Index(name = "idx_auth_roles_name", columnList = "name", unique = true),
        @Index(name = "idx_auth_roles_source", columnList = "source")
    }
)
public class AuthRole extends PanacheEntityBase {

    @Id
    public Long id;

    /**
     * The application this role belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    public Application application;

    /**
     * Full role name with application prefix (e.g., "platform:tenant-admin").
     */
    @Column(name = "name", unique = true, nullable = false, length = 100)
    public String name;

    /**
     * Human-readable display name (e.g., "Tenant Administrator").
     */
    @Column(name = "display_name", length = 100)
    public String displayName;

    /**
     * Description of what this role grants access to.
     */
    @Column(name = "description", length = 500)
    public String description;

    /**
     * Set of permission strings granted by this role.
     * Stored as JSON array.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", nullable = false, columnDefinition = "jsonb")
    public Set<String> permissions = new HashSet<>();

    /**
     * Source of this role definition.
     * CODE = from @Role Java classes (synced at startup)
     * DATABASE = created by admin through UI
     * SDK = registered by external application
     */
    @Column(name = "source", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    public RoleSource source = RoleSource.DATABASE;

    /**
     * If true, this role syncs to IDPs configured for client-managed roles.
     * Used for selective IDP synchronization.
     */
    @Column(name = "client_managed", nullable = false)
    public boolean clientManaged = false;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

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

    public AuthRole() {
    }

    /**
     * Create a role for an application.
     * The name should already include the application prefix.
     */
    public AuthRole(Application application, String name, String description, Set<String> permissions, RoleSource source) {
        this.application = application;
        this.name = name;
        this.description = description;
        this.permissions = permissions != null ? permissions : new HashSet<>();
        this.source = source;
    }

    /**
     * Extract the role name without the application prefix.
     */
    public String getShortName() {
        if (name != null && name.contains(":")) {
            return name.substring(name.indexOf(':') + 1);
        }
        return name;
    }

    /**
     * Source of a role definition.
     */
    public enum RoleSource {
        /** Defined in Java @Role classes, synced to DB at startup */
        CODE,
        /** Created by administrators through the UI */
        DATABASE,
        /** Registered by external applications via the SDK API */
        SDK
    }
}
