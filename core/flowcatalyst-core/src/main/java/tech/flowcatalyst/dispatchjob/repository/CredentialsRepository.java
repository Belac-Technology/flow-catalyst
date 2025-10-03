package tech.flowcatalyst.dispatchjob.repository;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import tech.flowcatalyst.dispatchjob.dto.CreateCredentialsRequest;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;
import tech.flowcatalyst.dispatchjob.model.SignatureAlgorithm;
import tech.flowcatalyst.dispatchjob.util.TsidGenerator;

import java.time.Instant;

/**
 * PostgreSQL repository for DispatchCredentials using Hibernate ORM Panache.
 * Credentials are stored in separate table (not embedded).
 *
 * Only active when dispatch-jobs.enabled=true
 */
@ApplicationScoped
@IfBuildProperty(name = "dispatch-jobs.enabled", stringValue = "true", enableIfMissing = true)
public class CredentialsRepository implements PanacheRepositoryBase<DispatchCredentials, Long> {

    /**
     * Create new credentials
     */
    @Transactional
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
