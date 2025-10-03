package tech.flowcatalyst.auth.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * SECURITY: Explicit authorization of IDP roles to internal roles.
 * Only IDP roles in this table are accepted during login.
 * This prevents partners/customers from injecting unauthorized roles.
 */
@Entity
@Table(name = "idp_role_mappings",
    indexes = {
        @Index(name = "idx_idp_role_name", columnList = "idp_role_name", unique = true)
    }
)
public class IdpRoleMapping extends PanacheEntityBase {

    @Id
    public Long id;

    /**
     * Role name from the IDP (e.g., Keycloak role name)
     */
    @Column(name = "idp_role_name", unique = true, nullable = false, length = 100)
    public String idpRoleName;

    /**
     * Internal role this IDP role maps to
     */
    @Column(name = "internal_role_id", nullable = false)
    public Long internalRoleId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.auth.util.TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public IdpRoleMapping() {
    }
}
