package tech.flowcatalyst.platform.authentication.oidc;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of OidcLoginStateRepository.
 * Package-private to prevent direct injection - use OidcLoginStateRepository interface.
 */
@ApplicationScoped
@Typed(OidcLoginStateRepository.class)
@Instrumented(collection = "oidc_login_states")
class MongoOidcLoginStateRepository implements PanacheMongoRepositoryBase<OidcLoginState, String>, OidcLoginStateRepository {

    @Override
    public Optional<OidcLoginState> findValidState(String state) {
        return find("_id = ?1 and expiresAt > ?2", state, Instant.now()).firstResultOptional();
    }

    @Override
    public void deleteExpired() {
        delete("expiresAt < ?1", Instant.now());
    }

    @Override
    public void deleteByState(String state) {
        deleteById(state);
    }

    // Delegate to Panache methods via interface
    @Override
    public OidcLoginState findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<OidcLoginState> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<OidcLoginState> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(OidcLoginState state) {
        PanacheMongoRepositoryBase.super.persist(state);
    }

    @Override
    public void update(OidcLoginState state) {
        PanacheMongoRepositoryBase.super.update(state);
    }

    @Override
    public void delete(OidcLoginState state) {
        PanacheMongoRepositoryBase.super.delete(state);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
