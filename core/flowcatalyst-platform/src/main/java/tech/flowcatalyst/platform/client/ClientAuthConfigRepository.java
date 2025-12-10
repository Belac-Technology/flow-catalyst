package tech.flowcatalyst.platform.client;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import tech.flowcatalyst.platform.authentication.AuthProvider;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ClientAuthConfig entities.
 * Used to look up authentication configuration by email domain.
 */
@ApplicationScoped
public class ClientAuthConfigRepository implements PanacheMongoRepositoryBase<ClientAuthConfig, String> {

    /**
     * Find authentication configuration by email domain.
     *
     * @param emailDomain The email domain (e.g., "acmecorp.com")
     * @return Optional containing the config if found
     */
    public Optional<ClientAuthConfig> findByEmailDomain(String emailDomain) {
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
    public List<ClientAuthConfig> findByAuthProvider(AuthProvider provider) {
        return find("authProvider", provider).list();
    }
}
