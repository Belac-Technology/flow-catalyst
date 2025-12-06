package tech.flowcatalyst.platform.client;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
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
@MongoEntity(collection = "client_auth_config")
public class ClientAuthConfig extends PanacheMongoEntityBase {

    @BsonId
    public Long id; // TSID

    public String emailDomain;

    public AuthProvider authProvider;

    public String oidcIssuerUrl;

    public String oidcClientId;

    public String oidcClientSecretEncrypted;

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
}
