package tech.flowcatalyst.auth.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.auth.model.Principal;

import java.util.Optional;

/**
 * Repository for Principal entities.
 */
@ApplicationScoped
public class PrincipalRepository implements PanacheRepositoryBase<Principal, Long> {

    public Optional<Principal> findByEmail(String email) {
        return find("userIdentity.email", email).firstResultOptional();
    }

    public Optional<Principal> findByClientId(String clientId) {
        return find("serviceAccount.clientId", clientId).firstResultOptional();
    }

    public Optional<Principal> findByExternalIdpId(String externalIdpId) {
        return find("userIdentity.externalIdpId", externalIdpId).firstResultOptional();
    }
}
