package tech.flowcatalyst.platform.application;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * Represents an application in the FlowCatalyst platform ecosystem.
 *
 * Applications are the software products that users access. Each application
 * has a unique code that is used as the prefix for roles (e.g., "inmotion:dispatch:admin").
 *
 * Application access is determined by roles:
 * - If a user has any role prefixed with the application code, they can access that app
 * - Anchor domain users get their roles applied to ALL tenants
 * - Partner users get their roles applied to GRANTED tenants only
 * - Tenant users get their roles applied to their OWN tenant only
 */
@MongoEntity(collection = "auth_applications")
public class Application extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    /**
     * Unique application code used in role prefixes.
     * Examples: "inmotion", "dispatch", "analytics", "platform"
     */
    public String code;

    public String name;

    public String description;

    public String iconUrl;

    /**
     * Default base URL for the application.
     * Can be overridden per tenant via ApplicationTenantConfig.
     */
    public String defaultBaseUrl;

    public boolean active = true;

    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

    public Application() {
    }

    public Application(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
