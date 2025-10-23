package tech.flowcatalyst.auth.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Role definition with permissions stored as JSON.
 * Roles are application-level, not tenant-specific.
 */
@Entity
@Table(name = "roles",
    indexes = {
        @Index(name = "idx_role_name", columnList = "name", unique = true)
    }
)
public class Role extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "name", unique = true, nullable = false, length = 100)
    public String name;

    @Column(name = "description", length = 500)
    public String description;

    /**
     * System roles cannot be deleted (e.g., platform-admin)
     */
    @Column(name = "is_system", nullable = false)
    public boolean isSystem = false;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    /**
     * Permissions stored as JSON array
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "permissions", columnDefinition = "jsonb")
    public List<Permission> permissions = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.auth.util.TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Role() {
    }
}
