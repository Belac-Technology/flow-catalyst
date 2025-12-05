package tech.flowcatalyst.platform.client;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Repository for ClientAccessGrant entities.
 */
@ApplicationScoped
public class ClientAccessGrantRepository implements PanacheRepositoryBase<ClientAccessGrant, Long> {

    public List<ClientAccessGrant> findByPrincipalId(Long principalId) {
        return find("principalId", principalId).list();
    }

    public List<ClientAccessGrant> findByClientId(Long clientId) {
        return find("clientId", clientId).list();
    }
}
