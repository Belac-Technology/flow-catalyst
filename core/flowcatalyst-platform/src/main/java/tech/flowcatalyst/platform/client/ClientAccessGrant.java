package tech.flowcatalyst.platform.client;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * Grants a principal (typically partner) access to a client.
 * Used for partners who work with multiple customers.
 */
@MongoEntity(collection = "client_access_grants")
public class ClientAccessGrant extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    public Long principalId;

    public Long clientId;

    public Instant grantedAt = Instant.now();

    public Instant expiresAt;

    public ClientAccessGrant() {
    }
}
