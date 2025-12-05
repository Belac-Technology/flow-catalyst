package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * Stores refresh tokens for long-lived sessions.
 *
 * Features:
 * - Token rotation: Each use issues a new refresh token
 * - Family tracking: All tokens in a family are invalidated on reuse detection
 * - Revocation: Tokens can be explicitly revoked
 *
 * Security: Only the token hash is stored, not the actual token.
 */
@Entity
@Table(name = "auth_refresh_tokens",
    indexes = {
        @Index(name = "idx_refresh_token_principal", columnList = "principal_id"),
        @Index(name = "idx_refresh_token_family", columnList = "token_family"),
        @Index(name = "idx_refresh_token_expires", columnList = "expires_at")
    }
)
public class RefreshToken extends PanacheEntityBase {

    /**
     * SHA-256 hash of the refresh token.
     * We store the hash, not the plain token, for security.
     */
    @Id
    @Column(name = "token_hash", length = 64)
    public String tokenHash;

    /**
     * The principal this token was issued for.
     */
    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    /**
     * OAuth client that requested this token.
     */
    @Column(name = "client_id", length = 100)
    public String clientId;

    /**
     * Client context for this token.
     */
    @Column(name = "context_client_id")
    public Long contextClientId;

    /**
     * Scopes granted with this token.
     */
    @Column(name = "scope", length = 500)
    public String scope;

    /**
     * Token family for refresh token rotation.
     *
     * All tokens in a family are invalidated if reuse is detected
     * (i.e., an old token is used after a newer one was issued).
     * This protects against token theft.
     */
    @Column(name = "token_family", nullable = false, length = 64)
    public String tokenFamily;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    /**
     * Whether this token has been revoked.
     */
    @Column(name = "revoked", nullable = false)
    public boolean revoked = false;

    /**
     * When this token was revoked (null if not revoked).
     */
    @Column(name = "revoked_at")
    public Instant revokedAt;

    /**
     * Hash of the token that replaced this one (for rotation tracking).
     */
    @Column(name = "replaced_by", length = 64)
    public String replacedBy;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }
}
