package tech.flowcatalyst.platform.authentication;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * SECURITY: Explicit authorization of IDP roles to internal roles.
 * Only IDP roles in this table are accepted during login.
 * This prevents partners/customers from injecting unauthorized roles.
 *
 * Maps external IDP role names to internal string-based role names.
 * Internal roles must be defined in PermissionRegistry.
 */
@Entity
@Table(name = "idp_role_mappings",
    indexes = {
        @Index(name = "idx_idp_role_name", columnList = "idp_role_name", unique = true),
        @Index(name = "idx_internal_role_name", columnList = "internal_role_name")
    }
)
public class IdpRoleMapping extends PanacheEntityBase {

    @Id
    public Long id;

    /**
     * Role name from the external IDP (e.g., Keycloak role name from partner IDP).
     * This is the role name that appears in the OIDC token.
     */
    @Column(name = "idp_role_name", unique = true, nullable = false, length = 100)
    public String idpRoleName;

    /**
     * Internal role name this IDP role maps to (e.g., "platform:tenant-admin").
     * Must match a role defined in PermissionRegistry.
     */
    @Column(name = "internal_role_name", nullable = false, length = 100)
    public String internalRoleName;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.platform.shared.TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public IdpRoleMapping() {
    }
}
