package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of OAuthClientRepository.
 * Package-private to prevent direct injection - use OAuthClientRepository interface.
 */
@ApplicationScoped
@Typed(OAuthClientRepository.class)
@Instrumented(collection = "oauth_clients")
class MongoOAuthClientRepository implements PanacheMongoRepositoryBase<OAuthClient, String>, OAuthClientRepository {

    @Override
    public Optional<OAuthClient> findByClientId(String clientId) {
        return find("clientId = ?1 and active = true", clientId).firstResultOptional();
    }

    @Override
    public Optional<OAuthClient> findByClientIdIncludingInactive(String clientId) {
        return find("clientId", clientId).firstResultOptional();
    }

    @Override
    public List<OAuthClient> findByTenantId(Long tenantId) {
        return find("tenantId = ?1 and active = true", tenantId).list();
    }

    @Override
    public List<OAuthClient> findAllActive() {
        return find("active", true).list();
    }

    @Override
    public List<OAuthClient> findByApplicationIdAndActive(String applicationId, boolean active) {
        return find("?1 in applicationIds AND active = ?2", applicationId, active).list();
    }

    @Override
    public List<OAuthClient> findByApplicationId(String applicationId) {
        return find("?1 in applicationIds", applicationId).list();
    }

    @Override
    public List<OAuthClient> findByActive(boolean active) {
        return find("active", active).list();
    }

    // Delegate to Panache methods via interface
    @Override
    public OAuthClient findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<OAuthClient> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<OAuthClient> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(OAuthClient client) {
        PanacheMongoRepositoryBase.super.persist(client);
    }

    @Override
    public void update(OAuthClient client) {
        PanacheMongoRepositoryBase.super.update(client);
    }

    @Override
    public void delete(OAuthClient client) {
        PanacheMongoRepositoryBase.super.delete(client);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }

    @Override
    public long deleteByServiceAccountPrincipalId(String principalId) {
        return delete("serviceAccountPrincipalId", principalId);
    }
}
