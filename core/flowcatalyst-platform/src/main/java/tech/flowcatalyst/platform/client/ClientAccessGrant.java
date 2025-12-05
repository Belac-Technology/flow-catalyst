package tech.flowcatalyst.platform.client;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Grants a principal (typically partner) access to a client.
 * Used for partners who work with multiple customers.
 */
@Entity
@Table(name = "auth_client_access_grants",
    indexes = {
        @Index(name = "idx_auth_client_grants_principal", columnList = "principal_id"),
        @Index(name = "idx_auth_client_grants_client", columnList = "client_id"),
        @Index(name = "idx_auth_client_grants_unique", columnList = "principal_id, client_id", unique = true)
    }
)
public class ClientAccessGrant extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    @Column(name = "client_id", nullable = false)
    public Long clientId;

    @Column(name = "granted_at", nullable = false)
    public Instant grantedAt = Instant.now();

    @Column(name = "expires_at")
    public Instant expiresAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.platform.shared.TsidGenerator.generate();
        }
        if (grantedAt == null) {
            grantedAt = Instant.now();
        }
    }

    public ClientAccessGrant() {
    }
}
