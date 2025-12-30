package tech.flowcatalyst.platform.principal;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of PrincipalRepository.
 * Package-private to prevent direct injection - use PrincipalRepository interface.
 */
@ApplicationScoped
@Typed(PrincipalRepository.class)
@Instrumented(collection = "principals")
class MongoPrincipalRepository implements PanacheMongoRepositoryBase<Principal, String>, PrincipalRepository {

    @Override
    public Optional<Principal> findByEmail(String email) {
        return find("userIdentity.email", email).firstResultOptional();
    }

    @Override
    public Optional<Principal> findByServiceAccountClientId(String clientId) {
        return find("serviceAccount.clientId", clientId).firstResultOptional();
    }

    @Override
    public Optional<Principal> findByExternalIdpId(String externalIdpId) {
        return find("userIdentity.externalIdpId", externalIdpId).firstResultOptional();
    }

    @Override
    public Optional<Principal> findByServiceAccountCode(String code) {
        return find("serviceAccount.code", code).firstResultOptional();
    }

    @Override
    public List<Principal> findByType(PrincipalType type) {
        return find("type", type).list();
    }

    @Override
    public List<Principal> findByClientId(String clientId) {
        return find("clientId", clientId).list();
    }

    @Override
    public List<Principal> findByIds(Collection<String> ids) {
        return find("id in ?1", ids).list();
    }

    @Override
    public List<Principal> findByClientIdAndType(String clientId, PrincipalType type) {
        return find("clientId = ?1 AND type = ?2", clientId, type).list();
    }

    @Override
    public List<Principal> findByClientIdAndTypeAndActive(String clientId, PrincipalType type, boolean active) {
        return find("clientId = ?1 AND type = ?2 AND active = ?3", clientId, type, active).list();
    }

    @Override
    public List<Principal> findByClientIdAndActive(String clientId, boolean active) {
        return find("clientId = ?1 AND active = ?2", clientId, active).list();
    }

    @Override
    public List<Principal> findByActive(boolean active) {
        return find("active", active).list();
    }

    @Override
    public List<Principal> findUsersByClientId(String clientId) {
        return find("clientId = ?1 AND type = ?2", clientId, PrincipalType.USER).list();
    }

    @Override
    public List<Principal> findActiveUsersByClientId(String clientId) {
        return find("clientId = ?1 AND type = ?2 AND active = true", clientId, PrincipalType.USER).list();
    }

    @Override
    public long countByEmailDomain(String emailDomain) {
        return count("userIdentity.emailDomain", emailDomain);
    }

    // Delegate to Panache methods via interface
    @Override
    public Principal findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<Principal> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<Principal> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(Principal principal) {
        PanacheMongoRepositoryBase.super.persist(principal);
    }

    @Override
    public void update(Principal principal) {
        PanacheMongoRepositoryBase.super.update(principal);
    }

    @Override
    public void delete(Principal principal) {
        PanacheMongoRepositoryBase.super.delete(principal);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
