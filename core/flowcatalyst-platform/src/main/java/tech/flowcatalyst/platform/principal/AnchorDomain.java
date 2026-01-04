package tech.flowcatalyst.platform.principal;

import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * Email domains that have god-mode access to all tenants.
 * Users from anchor domains can access any tenant without explicit grants.
 */
public class AnchorDomain {

    @BsonId
    public String id;

    public String domain; // e.g., "flowcatalyst.tech"

    public Instant createdAt = Instant.now();

    public AnchorDomain() {
    }
}
