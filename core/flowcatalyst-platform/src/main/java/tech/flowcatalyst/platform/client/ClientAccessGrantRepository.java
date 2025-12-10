package tech.flowcatalyst.platform.client;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ClientAccessGrant entities.
 */
@ApplicationScoped
public class ClientAccessGrantRepository implements PanacheMongoRepositoryBase<ClientAccessGrant, String> {

    public List<ClientAccessGrant> findByPrincipalId(String principalId) {
        return find("principalId", principalId).list();
    }

    public List<ClientAccessGrant> findByClientId(String clientId) {
        return find("clientId", clientId).list();
    }

    public Optional<ClientAccessGrant> findByPrincipalIdAndClientId(String principalId, String clientId) {
        return find("principalId = ?1 and clientId = ?2", principalId, clientId).firstResultOptional();
    }

    public boolean existsByPrincipalIdAndClientId(String principalId, String clientId) {
        return count("principalId = ?1 and clientId = ?2", principalId, clientId) > 0;
    }

    public void deleteByPrincipalId(String principalId) {
        delete("principalId", principalId);
    }
}
