package tech.flowcatalyst.auth.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tenant organization.
 * Only customers get tenants (partners don't).
 */
@Entity
@Table(name = "tenants",
    indexes = {
        @Index(name = "idx_tenant_identifier", columnList = "identifier", unique = true)
    }
)
public class Tenant extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "identifier", unique = true, nullable = false, length = 100)
    public String identifier; // Unique tenant slug/code

    @Column(name = "active", nullable = false)
    public boolean active = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

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

    public Tenant() {
    }
}
