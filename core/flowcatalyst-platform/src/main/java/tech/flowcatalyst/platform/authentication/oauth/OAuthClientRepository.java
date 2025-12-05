package tech.flowcatalyst.platform.authentication.oauth;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class OAuthClientRepository implements PanacheRepositoryBase<OAuthClient, Long> {

    public Optional<OAuthClient> findByClientId(String clientId) {
        return find("clientId = ?1 AND active = true", clientId).firstResultOptional();
    }

    public Optional<OAuthClient> findByClientIdIncludingInactive(String clientId) {
        return find("clientId", clientId).firstResultOptional();
    }

    public List<OAuthClient> findByTenantId(Long tenantId) {
        return find("tenantId = ?1 AND active = true", tenantId).list();
    }

    public List<OAuthClient> findAllActive() {
        return find("active = true").list();
    }
}
