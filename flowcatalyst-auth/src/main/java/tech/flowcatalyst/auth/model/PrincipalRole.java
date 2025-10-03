package tech.flowcatalyst.auth.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Junction table for many-to-many relationship between principals and roles.
 * IDP roles apply globally to all accessible tenants.
 */
@Entity
@Table(name = "principal_roles",
    indexes = {
        @Index(name = "idx_principal_roles_principal", columnList = "principal_id"),
        @Index(name = "idx_principal_roles_role", columnList = "role_id"),
        @Index(name = "idx_principal_roles_unique", columnList = "principal_id, role_id", unique = true)
    }
)
public class PrincipalRole extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    @Column(name = "role_id", nullable = false)
    public Long roleId;

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
            id = tech.flowcatalyst.auth.util.TsidGenerator.generate();
        }
        if (assignedAt == null) {
            assignedAt = Instant.now();
        }
    }

    public PrincipalRole() {
    }
}
