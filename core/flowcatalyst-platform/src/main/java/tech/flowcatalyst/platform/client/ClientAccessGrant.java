package tech.flowcatalyst.platform.client;

import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;

/**
 * Grants a principal (typically partner) access to a client.
 * Used for partners who work with multiple customers.
 */
public class ClientAccessGrant {

    @BsonId
    public String id;

    public String principalId;

    public String clientId;

    public Instant grantedAt = Instant.now();

    public Instant expiresAt;

    public ClientAccessGrant() {
    }
}
