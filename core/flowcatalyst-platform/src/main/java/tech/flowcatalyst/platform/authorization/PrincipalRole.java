package tech.flowcatalyst.platform.authorization;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Junction table for many-to-many relationship between principals and roles.
 * IDP roles apply globally to all accessible tenants.
 *
 * Uses string-based role names (e.g., "platform:tenant-admin") instead of role IDs.
 * Role definitions are code-first and stored in PermissionRegistry.
 */
@Entity
@Table(name = "principal_roles",
    indexes = {
        @Index(name = "idx_principal_roles_principal", columnList = "principal_id"),
        @Index(name = "idx_principal_roles_role_name", columnList = "role_name"),
        @Index(name = "idx_principal_roles_unique", columnList = "principal_id, role_name", unique = true)
    }
)
public class PrincipalRole extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    /**
     * String role name (e.g., "platform:tenant-admin", "logistics:dispatcher").
     * Must match a role defined in PermissionRegistry.
     */
    @Column(name = "role_name", nullable = false, length = 100)
    public String roleName;

    /**
     * How this role was assigned (MANUAL, IDP_SYNC)
     */
    @Column(name = "assignment_source", length = 50)
    public String assignmentSource;

    @Column(name = "assigned_at", nullable = false)
    public Instant assignedAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.platform.shared.TsidGenerator.generate();
        }
        if (assignedAt == null) {
            assignedAt = Instant.now();
        }
    }

    public PrincipalRole() {
    }
}
