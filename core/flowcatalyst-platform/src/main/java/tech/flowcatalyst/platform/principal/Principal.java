package tech.flowcatalyst.platform.principal;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

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

    public Principal() {
    }
}
