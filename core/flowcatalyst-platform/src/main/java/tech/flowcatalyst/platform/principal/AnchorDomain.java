package tech.flowcatalyst.platform.principal;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * Email domains that have god-mode access to all tenants.
 * Users from anchor domains can access any tenant without explicit grants.
 */
@MongoEntity(collection = "anchor_domains")
public class AnchorDomain extends PanacheMongoEntityBase {

    @BsonId
    public String id;

    public String domain; // e.g., "flowcatalyst.tech"

    public Instant createdAt = Instant.now();

    public AnchorDomain() {
    }
}
