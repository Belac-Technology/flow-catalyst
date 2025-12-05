package tech.flowcatalyst.platform.principal;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.platform.principal.Principal;

import java.util.Optional;

/**
 * Repository for Principal entities.
 */
@ApplicationScoped
public class PrincipalRepository implements PanacheRepositoryBase<Principal, Long> {

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
}
