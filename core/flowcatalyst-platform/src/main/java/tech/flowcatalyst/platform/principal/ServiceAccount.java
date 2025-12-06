package tech.flowcatalyst.platform.principal;

import java.time.Instant;

/**
 * Service account information (embedded in Principal for SERVICE type).
 */
public class ServiceAccount {

    public String code;

    public String description;

    public String clientId;

    public String clientSecretHash; // Argon2id hash

    public Instant lastUsedAt;

    public ServiceAccount() {
    }
}
