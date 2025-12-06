package tech.flowcatalyst.platform.application;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;
import java.util.Map;

/**
 * Per-client configuration for an application.
 *
 * Allows clients to have:
 * - Custom base URL (e.g., client1.inmotion.com instead of inmotion.com)
 * - Enabled/disabled status per application
 * - Custom configuration settings
 */
@MongoEntity(collection = "application_client_config")
public class ApplicationClientConfig extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    public Long applicationId;

    public Long clientId;

    /**
     * Whether this client has access to this application.
     * Even if a user has roles for an app, the app must be enabled for their client.
     */
    public boolean enabled = true;

    /**
     * Client-specific URL override.
     * If set, this URL is used instead of the application's defaultBaseUrl.
     * Example: "client1.inmotion.com" instead of "inmotion.com"
     */
    public String baseUrlOverride;

    /**
     * Additional client-specific configuration as JSON.
     * Can include branding, feature flags, etc.
     */
    public Map<String, Object> configJson;

    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

    public ApplicationClientConfig() {
    }
}
