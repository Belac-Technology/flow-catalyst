package tech.flowcatalyst.auth.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Grants a principal (typically partner) access to a tenant.
 * Used for partners who work with multiple customers.
 */
@Entity
@Table(name = "tenant_access_grants",
    indexes = {
        @Index(name = "idx_tenant_grants_principal", columnList = "principal_id"),
        @Index(name = "idx_tenant_grants_tenant", columnList = "tenant_id"),
        @Index(name = "idx_tenant_grants_unique", columnList = "principal_id, tenant_id", unique = true)
    }
)
public class TenantAccessGrant extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    @Column(name = "tenant_id", nullable = false)
    public Long tenantId;

    @Column(name = "granted_at", nullable = false)
    public Instant grantedAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.auth.util.TsidGenerator.generate();
        }
        if (grantedAt == null) {
            grantedAt = Instant.now();
        }
    }

    public TenantAccessGrant() {
    }
}
