package tech.flowcatalyst.platform.client;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonIgnore;
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
 *
 * IMPORTANT: The oidcClientSecretRef field stores a reference to the secret,
 * not the secret itself. Use ClientAuthConfigService to resolve the actual secret.
 */
@MongoEntity(collection = "client_auth_config")
public class ClientAuthConfig extends PanacheMongoEntityBase {

    @BsonId
    public String id; // TSID (Crockford Base32)

    /**
     * The email domain this configuration applies to (e.g., "acmecorp.com")
     */
    public String emailDomain;

    /**
     * The client this auth config belongs to (nullable for platform-wide configs)
     */
    public String clientId;

    /**
     * Authentication provider type: INTERNAL or OIDC
     */
    public AuthProvider authProvider;

    /**
     * OIDC issuer URL (e.g., "https://auth.customer.com/realms/main")
     * For multi-tenant IDPs like Entra, use the generic issuer:
     * - https://login.microsoftonline.com/organizations/v2.0
     */
    public String oidcIssuerUrl;

    /**
     * OIDC client ID
     */
    public String oidcClientId;

    /**
     * Whether this is a multi-tenant OIDC configuration.
     * When true, the issuer in tokens will vary by tenant (e.g., Entra ID).
     * The actual token issuer will be validated against oidcAllowedIssuers or
     * dynamically constructed using oidcIssuerPattern.
     */
    public boolean oidcMultiTenant = false;

    /**
     * Pattern for validating multi-tenant issuers.
     * Use {tenantId} as placeholder for the tenant ID.
     * Example: "https://login.microsoftonline.com/{tenantId}/v2.0"
     * If not set, defaults to deriving from oidcIssuerUrl.
     */
    public String oidcIssuerPattern;

    /**
     * Reference to the OIDC client secret.
     * This is NOT the plaintext secret - it's a reference for the SecretService.
     * Format depends on configured provider:
     * - encrypted:BASE64_CIPHERTEXT (default)
     * - aws-sm://secret-name
     * - aws-ps://parameter-name
     * - gcp-sm://projects/PROJECT/secrets/NAME
     * - vault://path/to/secret#key
     */
    public String oidcClientSecretRef;

    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

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

    /**
     * Check if this config has a client secret configured.
     */
    @BsonIgnore
    public boolean hasClientSecret() {
        return oidcClientSecretRef != null && !oidcClientSecretRef.isBlank();
    }

    /**
     * Get the issuer pattern for multi-tenant validation.
     * Returns the explicit pattern if set, otherwise derives from oidcIssuerUrl.
     * For Entra: replaces /organizations/ or /common/ with /{tenantId}/
     */
    @BsonIgnore
    public String getEffectiveIssuerPattern() {
        if (oidcIssuerPattern != null && !oidcIssuerPattern.isBlank()) {
            return oidcIssuerPattern;
        }
        if (oidcIssuerUrl == null) {
            return null;
        }
        // Auto-derive pattern for common multi-tenant IDPs
        return oidcIssuerUrl
            .replace("/organizations/", "/{tenantId}/")
            .replace("/common/", "/{tenantId}/")
            .replace("/consumers/", "/{tenantId}/");
    }

    /**
     * Validate if a token issuer is valid for this configuration.
     * For single-tenant: must match oidcIssuerUrl exactly.
     * For multi-tenant: must match the issuer pattern with any tenant ID.
     *
     * @param tokenIssuer The issuer claim from the token
     * @return true if the issuer is valid
     */
    @BsonIgnore
    public boolean isValidIssuer(String tokenIssuer) {
        if (tokenIssuer == null || tokenIssuer.isBlank()) {
            return false;
        }

        if (!oidcMultiTenant) {
            // Single tenant: exact match
            return tokenIssuer.equals(oidcIssuerUrl);
        }

        // Multi-tenant: match against pattern
        String pattern = getEffectiveIssuerPattern();
        if (pattern == null) {
            return false;
        }

        // Convert pattern to regex: {tenantId} -> [a-zA-Z0-9-]+
        String regex = pattern
            .replace(".", "\\.")
            .replace("{tenantId}", "[a-zA-Z0-9-]+");

        return tokenIssuer.matches(regex);
    }
}
