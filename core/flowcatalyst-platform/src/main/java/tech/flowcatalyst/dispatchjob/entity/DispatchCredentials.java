package tech.flowcatalyst.dispatchjob.entity;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import tech.flowcatalyst.dispatchjob.model.SignatureAlgorithm;
import java.time.Instant;

/**
 * Webhook authentication credentials stored in separate collection.
 * Referenced by ID from DispatchJob collection.
 */
@MongoEntity(collection = "dispatch_credentials")
public class DispatchCredentials extends PanacheMongoEntityBase {

    @BsonId
    public Long id;

    public String bearerToken;

    public String signingSecret;

    public SignatureAlgorithm algorithm = SignatureAlgorithm.HMAC_SHA256;

    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

    public DispatchCredentials() {
    }
}
