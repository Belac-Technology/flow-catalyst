package tech.flowcatalyst.platform.client;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import tech.flowcatalyst.platform.authentication.AuthProvider;

import java.time.Instant;

/**
 * Authentication configuration per email domain.
 * Determines whether users from a specific domain authenticate via
 * INTERNAL (password) or OIDC (external IDP).
 *
 * Example:
 * - acmecorp.com -> INTERNAL (users set passwords in FlowCatalyst)
 * - bigcustomer.com -> OIDC (redirect to customer's Keycloak)
 */
@Entity
@Table(name = "auth_client_auth_config")
public class ClientAuthConfig extends PanacheEntityBase {

    @Id
    public Long id; // TSID

    @Column(name = "email_domain", unique = true, nullable = false, length = 255)
    public String emailDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    public AuthProvider authProvider;

    @Column(name = "oidc_issuer_url", length = 500)
    public String oidcIssuerUrl;

    @Column(name = "oidc_client_id", length = 255)
    public String oidcClientId;

    @Column(name = "oidc_client_secret_encrypted", length = 1000)
    public String oidcClientSecretEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Validate OIDC configuration if provider is OIDC.
     * @throws IllegalStateException if OIDC is configured but required fields are missing
     */
    public void validateOidcConfig() {
        if (authProvider == AuthProvider.OIDC) {
            if (oidcIssuerUrl == null || oidcIssuerUrl.isBlank()) {
                throw new IllegalStateException("OIDC issuer URL is required for OIDC auth provider");
            }
            if (oidcClientId == null || oidcClientId.isBlank()) {
                throw new IllegalStateException("OIDC client ID is required for OIDC auth provider");
            }
        }
    }
}
