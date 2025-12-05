package tech.flowcatalyst.platform.principal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Instant;

/**
 * Service account information (embedded in Principal for SERVICE type).
 */
@Embeddable
public class ServiceAccount {

    @Column(name = "sa_code", length = 100)
    public String code;

    @Column(name = "sa_description", length = 500)
    public String description;

    @Column(name = "sa_client_id", unique = true, length = 100)
    public String clientId;

    @Column(name = "sa_client_secret_hash", length = 255)
    public String clientSecretHash; // Argon2id hash

    @Column(name = "sa_last_used_at")
    public Instant lastUsedAt;

    public ServiceAccount() {
    }
}
