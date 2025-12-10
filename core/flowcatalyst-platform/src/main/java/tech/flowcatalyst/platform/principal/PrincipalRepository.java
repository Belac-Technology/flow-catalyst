package tech.flowcatalyst.platform.principal;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Principal entities.
 */
@ApplicationScoped
public class PrincipalRepository implements PanacheMongoRepositoryBase<Principal, String> {

    public Optional<Principal> findByEmail(String email) {
        return find("userIdentity.email", email).firstResultOptional();
    }

    public Optional<Principal> findByServiceAccountClientId(String clientId) {
        return find("serviceAccount.clientId", clientId).firstResultOptional();
    }

    public Optional<Principal> findByExternalIdpId(String externalIdpId) {
        return find("userIdentity.externalIdpId", externalIdpId).firstResultOptional();
    }

    public Optional<Principal> findByServiceAccountCode(String code) {
        return find("serviceAccount.code", code).firstResultOptional();
    }

    public List<Principal> findByType(PrincipalType type) {
        return find("type", type).list();
    }

    public List<Principal> findByClientId(String clientId) {
        return find("clientId", clientId).list();
    }
}
