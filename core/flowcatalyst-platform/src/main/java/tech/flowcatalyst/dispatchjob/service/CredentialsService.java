package tech.flowcatalyst.dispatchjob.service;

import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.dispatchjob.dto.CreateCredentialsRequest;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;
import tech.flowcatalyst.dispatchjob.entity.DispatchJob;
import tech.flowcatalyst.dispatchjob.repository.CredentialsRepository;
import tech.flowcatalyst.platform.security.secrets.SecretService;
import tech.flowcatalyst.serviceaccount.entity.ServiceAccount;
import tech.flowcatalyst.serviceaccount.repository.ServiceAccountRepository;

import java.util.Optional;

/**
 * Service for managing dispatch credentials.
 */
@ApplicationScoped
public class CredentialsService {

    private static final Logger LOG = Logger.getLogger(CredentialsService.class);
    private static final String CACHE_NAME = "dispatch-credentials";
    private static final String SERVICE_ACCOUNT_CACHE = "service-account-credentials";

    @Inject
    CredentialsRepository credentialsRepository;

    @Inject
    ServiceAccountRepository serviceAccountRepository;

    @Inject
    SecretService secretService;

    /**
     * Resolved credentials for webhook signing.
     * Contains decrypted auth token and signing secret.
     */
    public record ResolvedCredentials(
        String authToken,
        String signingSecret
    ) {}

    /**
     * Resolve credentials for a dispatch job from either ServiceAccount or DispatchCredentials.
     *
     * <p>Looks up credentials in the following order:
     * <ol>
     *   <li>ServiceAccount (if serviceAccountId is set)</li>
     *   <li>DispatchCredentials (if credentialsId is set - legacy)</li>
     * </ol>
     *
     * @param job The dispatch job to resolve credentials for
     * @return Resolved credentials, or empty if not found
     */
    public Optional<ResolvedCredentials> resolveCredentials(DispatchJob job) {
        // First try ServiceAccount (new path)
        if (job.serviceAccountId != null) {
            return resolveFromServiceAccount(job.serviceAccountId);
        }

        // Fall back to legacy DispatchCredentials
        if (job.credentialsId != null) {
            return findById(job.credentialsId)
                .map(creds -> new ResolvedCredentials(creds.bearerToken, creds.signingSecret));
        }

        return Optional.empty();
    }

    /**
     * Resolve credentials from a ServiceAccount, decrypting the stored values.
     *
     * @param serviceAccountId The service account ID
     * @return Resolved credentials with decrypted values, or empty if not found
     */
    @CacheResult(cacheName = SERVICE_ACCOUNT_CACHE)
    public Optional<ResolvedCredentials> resolveFromServiceAccount(String serviceAccountId) {
        LOG.debugf("Loading service account credentials [%s] from database (cache miss)", serviceAccountId);

        return serviceAccountRepository.findByIdOptional(serviceAccountId)
            .filter(sa -> sa.active && sa.webhookCredentials != null)
            .map(sa -> {
                // Decrypt the credentials
                String authToken = secretService.resolve(sa.webhookCredentials.authTokenRef);
                String signingSecret = secretService.resolve(sa.webhookCredentials.signingSecretRef);
                return new ResolvedCredentials(authToken, signingSecret);
            });
    }

    /**
     * Invalidate service account credentials cache.
     */
    @CacheInvalidate(cacheName = SERVICE_ACCOUNT_CACHE)
    public void invalidateServiceAccountCache(String serviceAccountId) {
        LOG.debugf("Invalidated cache for service account credentials [%s]", serviceAccountId);
    }

    public DispatchCredentials create(CreateCredentialsRequest request) {
        DispatchCredentials credentials = credentialsRepository.create(request);
        LOG.infof("Created credentials [%s]", credentials.id);
        return credentials;
    }

    @CacheResult(cacheName = CACHE_NAME)
    public Optional<DispatchCredentials> findById(String id) {
        LOG.debugf("Loading credentials [%s] from database (cache miss)", id);
        return credentialsRepository.findByIdOptional(id);
    }

    @CacheInvalidate(cacheName = CACHE_NAME)
    public boolean delete(String id) {
        boolean deleted = credentialsRepository.deleteById(id);
        if (deleted) {
            LOG.infof("Deleted credentials [%s]", id);
        }
        return deleted;
    }

    @CacheInvalidate(cacheName = CACHE_NAME)
    public void invalidateCache(String id) {
        LOG.debugf("Invalidated cache for credentials [%s]", id);
    }
}
