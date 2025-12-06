package tech.flowcatalyst.platform.authorization;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * Represents a permission definition stored in the database.
 *
 * This table is optional and primarily used by external applications
 * that register their permissions via the SDK. Platform permissions
 * remain code-only (defined in @Permission Java classes).
 *
 * Permission format: {app}:{context}:{aggregate}:{action}
 * Example: "myapp:orders:order:create"
 */
@MongoEntity(collection = "auth_permissions")
public class AuthPermission extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    /**
     * The application this permission belongs to (stored as ID reference).
     */
    public Long applicationId;

    /**
     * Full permission name (e.g., "myapp:orders:order:create").
     */
    public String name;

    /**
     * Human-readable display name.
     */
    public String displayName;

    /**
     * Description of what this permission grants.
     */
    public String description;

    /**
     * Source of this permission definition.
     * SDK = registered by external application
     * DATABASE = created by admin
     */
    public PermissionSource source = PermissionSource.SDK;

    public Instant createdAt = Instant.now();

    public AuthPermission() {
    }

    public AuthPermission(Long applicationId, String name, String description, PermissionSource source) {
        this.applicationId = applicationId;
        this.name = name;
        this.description = description;
        this.source = source;
    }

    /**
     * Source of a permission definition.
     */
    public enum PermissionSource {
        /** Registered by external applications via the SDK API */
        SDK,
        /** Created by administrators */
        DATABASE
    }
}
