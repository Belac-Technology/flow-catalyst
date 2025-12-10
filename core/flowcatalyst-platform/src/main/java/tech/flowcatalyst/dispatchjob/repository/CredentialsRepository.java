package tech.flowcatalyst.dispatchjob.repository;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.dispatchjob.dto.CreateCredentialsRequest;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;
import tech.flowcatalyst.dispatchjob.model.SignatureAlgorithm;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * MongoDB repository for DispatchCredentials using Panache MongoDB.
 * Credentials are stored in separate collection (not embedded).
 */
@ApplicationScoped
public class CredentialsRepository implements PanacheMongoRepositoryBase<DispatchCredentials, String> {

    /**
     * Create new credentials
     */
    public DispatchCredentials create(CreateCredentialsRequest request) {
        DispatchCredentials credentials = new DispatchCredentials();
        credentials.id = TsidGenerator.generate();
        credentials.bearerToken = request.bearerToken();
        credentials.signingSecret = request.signingSecret();
        credentials.algorithm = request.algorithm() != null ?
            request.algorithm() : SignatureAlgorithm.HMAC_SHA256;
        credentials.createdAt = Instant.now();
        credentials.updatedAt = Instant.now();

        persist(credentials);
        return credentials;
    }
}
