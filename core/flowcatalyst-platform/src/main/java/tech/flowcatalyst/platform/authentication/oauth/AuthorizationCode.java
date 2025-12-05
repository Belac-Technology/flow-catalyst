package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * Stores authorization codes for the OAuth2 authorization code flow.
 *
 * Authorization codes are:
 * - Short-lived (default: 10 minutes)
 * - Single-use (marked as used after exchange)
 * - Bound to client, redirect URI, and PKCE challenge
 */
@Entity
@Table(name = "auth_authorization_codes",
    indexes = {
        @Index(name = "idx_auth_code_expires", columnList = "expires_at")
    }
)
public class AuthorizationCode extends PanacheEntityBase {

    /**
     * The authorization code value (64 char random string).
     */
    @Id
    @Column(length = 64)
    public String code;

    /**
     * OAuth client that initiated this authorization.
     */
    @Column(name = "client_id", nullable = false, length = 100)
    public String clientId;

    /**
     * The authenticated principal.
     */
    @Column(name = "principal_id", nullable = false)
    public Long principalId;

    /**
     * Redirect URI used in the authorization request.
     * Must match exactly during token exchange.
     */
    @Column(name = "redirect_uri", nullable = false, length = 1000)
    public String redirectUri;

    /**
     * Requested scopes.
     */
    @Column(name = "scope", length = 500)
    public String scope;

    /**
     * PKCE code challenge (required for public clients).
     */
    @Column(name = "code_challenge", length = 128)
    public String codeChallenge;

    /**
     * PKCE challenge method (S256 or plain).
     */
    @Column(name = "code_challenge_method", length = 10)
    public String codeChallengeMethod;

    /**
     * OIDC nonce for replay protection.
     */
    @Column(name = "nonce", length = 128)
    public String nonce;

    /**
     * Client-provided state for CSRF protection.
     */
    @Column(name = "state", length = 128)
    public String state;

    /**
     * Client context for the authorization.
     */
    @Column(name = "context_client_id")
    public Long contextClientId;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    /**
     * Whether this code has been used (single-use enforcement).
     */
    @Column(name = "used", nullable = false)
    public boolean used = false;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !used && !isExpired();
    }
}
