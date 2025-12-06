package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * OAuth2 client registration for SPAs, mobile apps, and service clients.
 *
 * Supports two client types:
 * - PUBLIC: For SPAs and mobile apps (no secret, PKCE required)
 * - CONFIDENTIAL: For server-side apps (has secret)
 */
@MongoEntity(collection = "oauth_clients")
public class OAuthClient extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    /**
     * Unique client identifier used in OAuth flows.
     * Example: "fc_ABCdef123"
     */
    public String clientId;

    /**
     * Human-readable name for this client.
     */
    public String clientName;

    /**
     * Client type determines security requirements.
     */
    public ClientType clientType;

    /**
     * Hashed client secret (null for public clients).
     */
    public String clientSecretHash;

    /**
     * Allowed redirect URIs (comma-separated).
     * Must match exactly during authorization.
     */
    public String redirectUris;

    /**
     * Allowed grant types (comma-separated).
     * Example: "authorization_code,refresh_token"
     */
    public String grantTypes;

    /**
     * Default scopes for this client.
     */
    public String defaultScopes;

    /**
     * Whether PKCE is required for authorization code flow.
     * Always true for public clients.
     */
    public boolean pkceRequired = true;

    /**
     * Optional: restrict OAuth client to specific owning client.
     * NULL means OAuth client can be used by any client.
     */
    public Long ownerClientId;

    public boolean active = true;

    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

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
