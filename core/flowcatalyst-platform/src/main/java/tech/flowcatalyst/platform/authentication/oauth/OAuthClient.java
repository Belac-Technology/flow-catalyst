package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * OAuth2 client registration for SPAs, mobile apps, and service clients.
 *
 * Supports two client types:
 * - PUBLIC: For SPAs and mobile apps (no secret, PKCE required)
 * - CONFIDENTIAL: For server-side apps (has secret)
 */
@Entity
@Table(name = "auth_oauth_clients",
    indexes = {
        @Index(name = "idx_oauth_client_client_id", columnList = "client_id"),
        @Index(name = "idx_oauth_client_owner", columnList = "owner_client_id")
    }
)
public class OAuthClient extends PanacheEntityBase {

    @Id
    public Long id;

    /**
     * Unique client identifier used in OAuth flows.
     * Example: "fc_ABCdef123"
     */
    @Column(name = "client_id", unique = true, nullable = false, length = 100)
    public String clientId;

    /**
     * Human-readable name for this client.
     */
    @Column(name = "client_name", nullable = false, length = 255)
    public String clientName;

    /**
     * Client type determines security requirements.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 20)
    public ClientType clientType;

    /**
     * Hashed client secret (null for public clients).
     */
    @Column(name = "client_secret_hash", length = 500)
    public String clientSecretHash;

    /**
     * Allowed redirect URIs (comma-separated).
     * Must match exactly during authorization.
     */
    @Column(name = "redirect_uris", nullable = false, length = 2000)
    public String redirectUris;

    /**
     * Allowed grant types (comma-separated).
     * Example: "authorization_code,refresh_token"
     */
    @Column(name = "grant_types", nullable = false, length = 200)
    public String grantTypes;

    /**
     * Default scopes for this client.
     */
    @Column(name = "default_scopes", length = 500)
    public String defaultScopes;

    /**
     * Whether PKCE is required for authorization code flow.
     * Always true for public clients.
     */
    @Column(name = "pkce_required", nullable = false)
    public boolean pkceRequired = true;

    /**
     * Optional: restrict OAuth client to specific owning client.
     * NULL means OAuth client can be used by any client.
     */
    @Column(name = "owner_client_id")
    public Long ownerClientId;

    @Column(name = "active", nullable = false)
    public boolean active = true;

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

    /**
     * Check if a redirect URI is allowed for this client.
     */
    public boolean isRedirectUriAllowed(String uri) {
        if (redirectUris == null || uri == null) {
            return false;
        }
        for (String allowed : redirectUris.split(",")) {
            if (allowed.trim().equals(uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a grant type is allowed for this client.
     */
    public boolean isGrantTypeAllowed(String grantType) {
        if (grantTypes == null || grantType == null) {
            return false;
        }
        for (String allowed : grantTypes.split(",")) {
            if (allowed.trim().equals(grantType)) {
                return true;
            }
        }
        return false;
    }

    public enum ClientType {
        /**
         * Public client (SPA, mobile app).
         * No client secret, PKCE required.
         */
        PUBLIC,

        /**
         * Confidential client (server-side app).
         * Has client secret.
         */
        CONFIDENTIAL
    }
}
