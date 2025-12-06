package tech.flowcatalyst.platform.principal;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified identity model for both users and service accounts.
 * Follows the architecture documented in docs/auth-architecture.md
 */
@MongoEntity(collection = "auth_principals")
public class Principal extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    public PrincipalType type;

    /**
     * Client this principal belongs to.
     * NULL for partners and anchor domain users.
     */
    public Long clientId;

    public String name;

    public boolean active = true;

    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

    /**
     * Embedded user identity (for USER type)
     */
    public UserIdentity userIdentity;

    /**
     * Embedded service account (for SERVICE type)
     */
    public ServiceAccount serviceAccount;

    /**
     * Embedded role assignments (denormalized for MongoDB).
     * This is the source of truth for principal roles.
     */
    public List<RoleAssignment> roles = new ArrayList<>();

    public Principal() {
    }

    /**
     * Get role names as a set for quick lookup.
     */
    public Set<String> getRoleNames() {
        return roles.stream()
            .map(r -> r.roleName)
            .collect(Collectors.toSet());
    }

    /**
     * Check if principal has a specific role.
     */
    public boolean hasRole(String roleName) {
        return roles.stream().anyMatch(r -> r.roleName.equals(roleName));
    }

    /**
     * Embedded role assignment.
     */
    public static class RoleAssignment {
        public String roleName;
        public String assignmentSource;
        public Instant assignedAt;

        public RoleAssignment() {
        }

        public RoleAssignment(String roleName, String assignmentSource) {
            this.roleName = roleName;
            this.assignmentSource = assignmentSource;
            this.assignedAt = Instant.now();
        }
    }
}
