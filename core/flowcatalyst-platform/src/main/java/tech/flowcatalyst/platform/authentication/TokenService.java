package tech.flowcatalyst.platform.authentication;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.flowcatalyst.platform.principal.PrincipalType;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Service for JWT token generation and validation.
 * Used for service accounts and internal authentication tokens.
 */
@ApplicationScoped
public class TokenService {

    @ConfigProperty(name = "flowcatalyst.auth.jwt.issuer", defaultValue = "flowcatalyst")
    String issuer;

    @ConfigProperty(name = "flowcatalyst.auth.jwt.expiry", defaultValue = "365d")
    Duration defaultExpiry;

    /**
     * Issue a JWT token for a principal (user or service account).
     *
     * @param principalId The principal ID
     * @param principalType The principal type (USER or SERVICE)
     * @param expiry Token expiry duration (null for default)
     * @return JWT token string
     */
    public String issueToken(Long principalId, PrincipalType principalType, Duration expiry) {
        if (expiry == null) {
            expiry = defaultExpiry;
        }

        return Jwt.issuer(issuer)
            .subject(String.valueOf(principalId))
            .claim("type", principalType.name())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plus(expiry))
            .sign();
    }

    /**
     * Issue a token with role claims for session management.
     *
     * @param principalId The principal ID
     * @param principalType The principal type
     * @param roles The set of role names
     * @param expiry Token expiry duration
     * @return JWT token string
     */
    public String issueTokenWithRoles(Long principalId, PrincipalType principalType, Set<String> roles, Duration expiry) {
        if (expiry == null) {
            expiry = defaultExpiry;
        }

        return Jwt.issuer(issuer)
            .subject(String.valueOf(principalId))
            .claim("type", principalType.name())
            .groups(roles)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plus(expiry))
            .sign();
    }

    /**
     * Issue a service account token (long-lived).
     *
     * @param principalId The service account principal ID
     * @return JWT token string
     */
    public String issueServiceAccountToken(Long principalId) {
        return issueToken(principalId, PrincipalType.SERVICE, defaultExpiry);
    }

    /**
     * Issue a session token (short-lived).
     *
     * @param principalId The user principal ID
     * @param roles User's roles
     * @return JWT token string
     */
    public String issueSessionToken(Long principalId, Set<String> roles) {
        // Session tokens expire in 8 hours
        return issueTokenWithRoles(principalId, PrincipalType.USER, roles, Duration.ofHours(8));
    }
}
