package tech.flowcatalyst.platform.client;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.authentication.AuthProvider;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of ClientAuthConfigRepository.
 * Package-private to prevent direct injection - use ClientAuthConfigRepository interface.
 */
@ApplicationScoped
@Typed(ClientAuthConfigRepository.class)
@Instrumented(collection = "client_auth_configs")
class MongoClientAuthConfigRepository implements PanacheMongoRepositoryBase<ClientAuthConfig, String>, ClientAuthConfigRepository {

    @Override
    public Optional<ClientAuthConfig> findByEmailDomain(String emailDomain) {
        return find("emailDomain", emailDomain).firstResultOptional();
    }

    @Override
    public boolean existsByEmailDomain(String emailDomain) {
        return find("emailDomain", emailDomain).count() > 0;
    }

    @Override
    public List<ClientAuthConfig> findByAuthProvider(AuthProvider provider) {
        return find("authProvider", provider).list();
    }

    @Override
    public List<ClientAuthConfig> findByClientId(String clientId) {
        return find("primaryClientId = ?1 or clientId = ?1", clientId).list();
    }

    @Override
    public List<ClientAuthConfig> findByConfigType(AuthConfigType configType) {
        return find("configType", configType).list();
    }

    // Delegate to Panache methods via interface
    @Override
    public ClientAuthConfig findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<ClientAuthConfig> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<ClientAuthConfig> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(ClientAuthConfig config) {
        PanacheMongoRepositoryBase.super.persist(config);
    }

    @Override
    public void update(ClientAuthConfig config) {
        PanacheMongoRepositoryBase.super.update(config);
    }

    @Override
    public void delete(ClientAuthConfig config) {
        PanacheMongoRepositoryBase.super.delete(config);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
