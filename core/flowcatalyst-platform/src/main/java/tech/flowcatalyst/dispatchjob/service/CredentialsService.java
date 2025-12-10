package tech.flowcatalyst.dispatchjob.service;

import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.dispatchjob.dto.CreateCredentialsRequest;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;
import tech.flowcatalyst.dispatchjob.repository.CredentialsRepository;

import java.util.Optional;

/**
 * Service for managing dispatch credentials.
 */
@ApplicationScoped
public class CredentialsService {

    private static final Logger LOG = Logger.getLogger(CredentialsService.class);
    private static final String CACHE_NAME = "dispatch-credentials";

    @Inject
    CredentialsRepository credentialsRepository;

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
