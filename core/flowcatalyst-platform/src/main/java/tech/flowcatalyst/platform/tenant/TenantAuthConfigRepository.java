package tech.flowcatalyst.platform.tenant;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.platform.authentication.AuthProvider;
import tech.flowcatalyst.platform.tenant.TenantAuthConfig;

import java.util.Optional;

/**
 * Repository for TenantAuthConfig entities.
 * Used to look up authentication configuration by email domain.
 */
@ApplicationScoped
public class TenantAuthConfigRepository implements PanacheRepositoryBase<TenantAuthConfig, Long> {

    /**
     * Find authentication configuration by email domain.
     *
     * @param emailDomain The email domain (e.g., "acmecorp.com")
     * @return Optional containing the config if found
     */
    public Optional<TenantAuthConfig> findByEmailDomain(String emailDomain) {
        return find("emailDomain", emailDomain).firstResultOptional();
    }

    /**
     * Check if a specific email domain has authentication configuration.
     *
     * @param emailDomain The email domain
     * @return true if configuration exists
     */
    public boolean existsByEmailDomain(String emailDomain) {
        return find("emailDomain", emailDomain).count() > 0;
    }

    /**
     * Find all configurations using a specific auth provider.
     *
     * @param provider The auth provider type
     * @return List of configurations
     */
    public java.util.List<TenantAuthConfig> findByAuthProvider(AuthProvider provider) {
        return find("authProvider", provider).list();
    }
}
