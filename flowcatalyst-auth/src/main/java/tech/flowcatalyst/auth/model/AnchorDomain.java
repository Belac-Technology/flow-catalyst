package tech.flowcatalyst.auth.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Email domains that have god-mode access to all tenants.
 * Users from anchor domains can access any tenant without explicit grants.
 */
@Entity
@Table(name = "anchor_domains",
    indexes = {
        @Index(name = "idx_anchor_domain", columnList = "domain", unique = true)
    }
)
public class AnchorDomain extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "domain", unique = true, nullable = false, length = 255)
    public String domain; // e.g., "flowcatalyst.tech"

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

    public AnchorDomain() {
    }
}
