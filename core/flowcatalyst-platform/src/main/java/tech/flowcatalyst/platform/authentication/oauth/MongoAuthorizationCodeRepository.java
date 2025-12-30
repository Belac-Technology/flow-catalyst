package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of AuthorizationCodeRepository.
 * Package-private to prevent direct injection - use AuthorizationCodeRepository interface.
 */
@ApplicationScoped
@Typed(AuthorizationCodeRepository.class)
@Instrumented(collection = "authorization_codes")
class MongoAuthorizationCodeRepository implements PanacheMongoRepositoryBase<AuthorizationCode, String>, AuthorizationCodeRepository {

    @Override
    public Optional<AuthorizationCode> findValidCode(String code) {
        return find("code = ?1 and used = false and expiresAt > ?2", code, Instant.now())
            .firstResultOptional();
    }

    @Override
    public void markAsUsed(String code) {
        update("used = true").where("code", code);
    }

    @Override
    public long deleteExpired() {
        return delete("expiresAt < ?1", Instant.now());
    }

    @Override
    public long deleteByPrincipalId(String principalId) {
        return delete("principalId", principalId);
    }

    // Delegate to Panache methods via interface
    @Override
    public AuthorizationCode findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<AuthorizationCode> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<AuthorizationCode> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(AuthorizationCode code) {
        PanacheMongoRepositoryBase.super.persist(code);
    }

    @Override
    public void update(AuthorizationCode code) {
        PanacheMongoRepositoryBase.super.update(code);
    }

    @Override
    public void delete(AuthorizationCode code) {
        PanacheMongoRepositoryBase.super.delete(code);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
