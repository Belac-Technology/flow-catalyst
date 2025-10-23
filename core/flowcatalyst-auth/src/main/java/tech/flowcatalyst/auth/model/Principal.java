package tech.flowcatalyst.auth.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Unified identity model for both users and service accounts.
 * Follows the architecture documented in docs/auth-architecture.md
 */
@Entity
@Table(name = "principals",
    indexes = {
        @Index(name = "idx_principal_tenant_id", columnList = "tenant_id"),
        @Index(name = "idx_principal_type", columnList = "type"),
        @Index(name = "idx_principal_user_email", columnList = "user_email"),
        @Index(name = "idx_principal_sa_client_id", columnList = "sa_client_id")
    }
)
public class Principal extends PanacheEntityBase {

    @Id
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    public PrincipalType type;

    /**
     * Tenant this principal belongs to.
     * NULL for partners and anchor domain users.
     */
    @Column(name = "tenant_id")
    public Long tenantId;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    /**
     * Embedded user identity (for USER type)
     */
    @Embedded
    public UserIdentity userIdentity;

    /**
     * Embedded service account (for SERVICE type)
     */
    @Embedded
    public ServiceAccount serviceAccount;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.auth.util.TsidGenerator.generate();
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

    public Principal() {
    }
}
