package tech.flowcatalyst.dispatchjob.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import tech.flowcatalyst.dispatchjob.model.SignatureAlgorithm;
import tech.flowcatalyst.dispatchjob.util.TsidGenerator;

import java.time.Instant;

/**
 * Webhook authentication credentials stored in separate table.
 * Referenced by ID from DispatchJob table.
 */
@Entity
@Table(name = "dispatch_credentials")
public class DispatchCredentials extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "bearer_token", length = 500)
    public String bearerToken;

    @Column(name = "signing_secret", length = 500)
    public String signingSecret;

    @Enumerated(EnumType.STRING)
    @Column(name = "algorithm", nullable = false, length = 50)
    public SignatureAlgorithm algorithm = SignatureAlgorithm.HMAC_SHA256;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

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

    public DispatchCredentials() {
    }
}
