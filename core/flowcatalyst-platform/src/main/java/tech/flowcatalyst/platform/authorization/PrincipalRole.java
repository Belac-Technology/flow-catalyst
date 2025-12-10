package tech.flowcatalyst.platform.authorization;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * Junction table for many-to-many relationship between principals and roles.
 * IDP roles apply globally to all accessible tenants.
 *
 * Uses string-based role names (e.g., "platform:tenant-admin") instead of role IDs.
 * Role definitions are code-first and stored in PermissionRegistry.
 */
@MongoEntity(collection = "principal_roles")
public class PrincipalRole extends PanacheMongoEntityBase {

    @BsonId
    public String id;

    public String principalId;

    /**
     * String role name (e.g., "platform:tenant-admin", "logistics:dispatcher").
     * Must match a role defined in PermissionRegistry.
     */
    public String roleName;

    /**
     * How this role was assigned (MANUAL, IDP_SYNC)
     */
    public String assignmentSource;

    public Instant assignedAt = Instant.now();

    public PrincipalRole() {
    }
}
