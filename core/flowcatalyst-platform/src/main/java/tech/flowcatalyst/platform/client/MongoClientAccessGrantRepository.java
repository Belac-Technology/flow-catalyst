package tech.flowcatalyst.platform.client;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of ClientAccessGrantRepository.
 * Package-private to prevent direct injection - use ClientAccessGrantRepository interface.
 */
@ApplicationScoped
@Typed(ClientAccessGrantRepository.class)
@Instrumented(collection = "client_access_grants")
class MongoClientAccessGrantRepository implements PanacheMongoRepositoryBase<ClientAccessGrant, String>, ClientAccessGrantRepository {

    @Override
    public List<ClientAccessGrant> findByPrincipalId(String principalId) {
        return find("principalId", principalId).list();
    }

    @Override
    public List<ClientAccessGrant> findByClientId(String clientId) {
        return find("clientId", clientId).list();
    }

    @Override
    public Optional<ClientAccessGrant> findByPrincipalIdAndClientId(String principalId, String clientId) {
        return find("principalId = ?1 and clientId = ?2", principalId, clientId).firstResultOptional();
    }

    @Override
    public boolean existsByPrincipalIdAndClientId(String principalId, String clientId) {
        return count("principalId = ?1 and clientId = ?2", principalId, clientId) > 0;
    }

    @Override
    public void deleteByPrincipalId(String principalId) {
        delete("principalId", principalId);
    }

    @Override
    public long deleteByPrincipalIdAndClientId(String principalId, String clientId) {
        return delete("principalId = ?1 AND clientId = ?2", principalId, clientId);
    }

    // Delegate to Panache methods via interface
    @Override
    public ClientAccessGrant findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<ClientAccessGrant> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<ClientAccessGrant> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(ClientAccessGrant grant) {
        PanacheMongoRepositoryBase.super.persist(grant);
    }

    @Override
    public void update(ClientAccessGrant grant) {
        PanacheMongoRepositoryBase.super.update(grant);
    }

    @Override
    public void delete(ClientAccessGrant grant) {
        PanacheMongoRepositoryBase.super.delete(grant);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
