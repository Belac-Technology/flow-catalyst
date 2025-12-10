package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OAuthClientRepository implements PanacheMongoRepositoryBase<OAuthClient, String> {

    public Optional<OAuthClient> findByClientId(String clientId) {
        return find("clientId = ?1 and active = true", clientId).firstResultOptional();
    }

    public Optional<OAuthClient> findByClientIdIncludingInactive(String clientId) {
        return find("clientId", clientId).firstResultOptional();
    }

    public List<OAuthClient> findByTenantId(Long tenantId) {
        return find("tenantId = ?1 and active = true", tenantId).list();
    }

    public List<OAuthClient> findAllActive() {
        return find("active", true).list();
    }
}
