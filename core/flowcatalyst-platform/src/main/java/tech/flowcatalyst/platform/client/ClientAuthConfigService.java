package tech.flowcatalyst.platform.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import tech.flowcatalyst.platform.authentication.AuthProvider;
import tech.flowcatalyst.platform.security.secrets.SecretProvider.ValidationResult;
import tech.flowcatalyst.platform.security.secrets.SecretService;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing ClientAuthConfig entities with secure secret handling.
 *
 * SECURITY MODEL:
 * - Secrets are stored in external secret managers (AWS, GCP, Vault) by infrastructure teams
 * - This service only stores and validates secret REFERENCES (URIs), never plaintext
 * - Secret resolution (getting plaintext) requires Super Admin role
 * - Validation (checking a reference is accessible) is safe for any admin
 */
@ApplicationScoped
public class ClientAuthConfigService {

    private static final Logger LOG = Logger.getLogger(ClientAuthConfigService.class);

    @Inject
    ClientAuthConfigRepository repository;

    @Inject
    SecretService secretService;

    /**
     * Find auth config by email domain.
     */
    public Optional<ClientAuthConfig> findByEmailDomain(String emailDomain) {
        return repository.findByEmailDomain(emailDomain.toLowerCase());
    }

    /**
     * Find auth config by ID.
     */
    public Optional<ClientAuthConfig> findById(String id) {
        return repository.findByIdOptional(id);
    }

    /**
     * List all auth configs.
     */
    public List<ClientAuthConfig> listAll() {
        return repository.listAll();
    }

    /**
     * List auth configs by client ID.
     */
    public List<ClientAuthConfig> findByClientId(String clientId) {
        return repository.find("clientId", clientId).list();
    }

    /**
     * Create a new auth config with INTERNAL authentication.
     *
     * @param emailDomain The email domain
     * @param clientId The client ID (nullable for platform-wide configs)
     * @return The created config
     */
    @Transactional
    public ClientAuthConfig createInternal(String emailDomain, String clientId) {
        return create(emailDomain, clientId, AuthProvider.INTERNAL, null, null, null, false, null);
    }

    /**
     * Create a new auth config with OIDC authentication.
     *
     * @param emailDomain The email domain
     * @param clientId The client ID (nullable for platform-wide configs)
     * @param oidcIssuerUrl OIDC issuer URL
     * @param oidcClientId OIDC client ID
     * @param oidcClientSecretRef Reference to secret in external store (e.g., aws-sm://secret-name)
     * @return The created config
     */
    @Transactional
    public ClientAuthConfig createOidc(
            String emailDomain,
            String clientId,
            String oidcIssuerUrl,
            String oidcClientId,
            String oidcClientSecretRef) {
        return createOidc(emailDomain, clientId, oidcIssuerUrl, oidcClientId, oidcClientSecretRef, false, null);
    }

    /**
     * Create a new auth config with OIDC authentication (with multi-tenant support).
     *
     * @param emailDomain The email domain
     * @param clientId The client ID (nullable for platform-wide configs)
     * @param oidcIssuerUrl OIDC issuer URL
     * @param oidcClientId OIDC client ID
     * @param oidcClientSecretRef Reference to secret in external store (e.g., aws-sm://secret-name)
     * @param oidcMultiTenant Whether this is a multi-tenant OIDC configuration
     * @param oidcIssuerPattern Pattern for validating multi-tenant issuers (e.g., https://login.microsoftonline.com/{tenantId}/v2.0)
     * @return The created config
     */
    @Transactional
    public ClientAuthConfig createOidc(
            String emailDomain,
            String clientId,
            String oidcIssuerUrl,
            String oidcClientId,
            String oidcClientSecretRef,
            boolean oidcMultiTenant,
            String oidcIssuerPattern) {
        return create(emailDomain, clientId, AuthProvider.OIDC, oidcIssuerUrl, oidcClientId,
                oidcClientSecretRef, oidcMultiTenant, oidcIssuerPattern);
    }

    /**
     * Create a new auth config.
     *
     * @param emailDomain The email domain
     * @param clientId The client ID (nullable for platform-wide configs)
     * @param authProvider The auth provider type
     * @param oidcIssuerUrl OIDC issuer URL (required for OIDC)
     * @param oidcClientId OIDC client ID (required for OIDC)
     * @param oidcClientSecretRef Reference to secret in external store (not plaintext!)
     * @param oidcMultiTenant Whether this is a multi-tenant OIDC configuration
     * @param oidcIssuerPattern Pattern for validating multi-tenant issuers
     * @return The created config
     */
    @Transactional
    public ClientAuthConfig create(
            String emailDomain,
            String clientId,
            AuthProvider authProvider,
            String oidcIssuerUrl,
            String oidcClientId,
            String oidcClientSecretRef,
            boolean oidcMultiTenant,
            String oidcIssuerPattern) {

        String normalizedDomain = emailDomain.toLowerCase();

        // Check for duplicate domain
        if (repository.existsByEmailDomain(normalizedDomain)) {
            throw new IllegalArgumentException("Auth config already exists for domain: " + normalizedDomain);
        }

        ClientAuthConfig config = new ClientAuthConfig();
        config.id = TsidGenerator.generate();
        config.emailDomain = normalizedDomain;
        config.clientId = clientId;
        config.authProvider = authProvider;
        config.createdAt = Instant.now();
        config.updatedAt = Instant.now();

        if (authProvider == AuthProvider.OIDC) {
            config.oidcIssuerUrl = oidcIssuerUrl;
            config.oidcClientId = oidcClientId;
            config.oidcMultiTenant = oidcMultiTenant;
            config.oidcIssuerPattern = oidcIssuerPattern;

            // Prepare secret reference for storage (encrypts if encrypt: prefix used)
            if (oidcClientSecretRef != null && !oidcClientSecretRef.isBlank()) {
                if (!secretService.isValidFormat(oidcClientSecretRef)) {
                    throw new IllegalArgumentException(
                        "Invalid secret reference format. Use encrypt:, aws-sm://, aws-ps://, gcp-sm://, or vault:// prefix");
                }
                config.oidcClientSecretRef = secretService.prepareForStorage(oidcClientSecretRef);
            }

            config.validateOidcConfig();
        }

        repository.persist(config);
        LOG.infof("Created auth config for domain: %s (provider: %s, multiTenant: %s)",
            normalizedDomain, authProvider, oidcMultiTenant);

        return config;
    }

    /**
     * Update an existing OIDC auth config.
     *
     * @param id The config ID
     * @param oidcIssuerUrl New OIDC issuer URL
     * @param oidcClientId New OIDC client ID
     * @param oidcClientSecretRef New reference to secret (not plaintext!)
     * @return The updated config
     */
    @Transactional
    public ClientAuthConfig updateOidc(
            String id,
            String oidcIssuerUrl,
            String oidcClientId,
            String oidcClientSecretRef) {
        return updateOidc(id, oidcIssuerUrl, oidcClientId, oidcClientSecretRef, null, null);
    }

    /**
     * Update an existing OIDC auth config with multi-tenant support.
     *
     * @param id The config ID
     * @param oidcIssuerUrl New OIDC issuer URL
     * @param oidcClientId New OIDC client ID
     * @param oidcClientSecretRef New reference to secret (not plaintext!)
     * @param oidcMultiTenant Whether this is a multi-tenant OIDC configuration (null to keep existing)
     * @param oidcIssuerPattern Pattern for validating multi-tenant issuers (null to keep existing)
     * @return The updated config
     */
    @Transactional
    public ClientAuthConfig updateOidc(
            String id,
            String oidcIssuerUrl,
            String oidcClientId,
            String oidcClientSecretRef,
            Boolean oidcMultiTenant,
            String oidcIssuerPattern) {

        ClientAuthConfig config = repository.findByIdOptional(id)
            .orElseThrow(() -> new IllegalArgumentException("Auth config not found: " + id));

        if (config.authProvider != AuthProvider.OIDC) {
            throw new IllegalArgumentException("Cannot update OIDC settings on non-OIDC config");
        }

        config.oidcIssuerUrl = oidcIssuerUrl;
        config.oidcClientId = oidcClientId;

        // Update multi-tenant settings if provided
        if (oidcMultiTenant != null) {
            config.oidcMultiTenant = oidcMultiTenant;
        }
        if (oidcIssuerPattern != null) {
            config.oidcIssuerPattern = oidcIssuerPattern.isBlank() ? null : oidcIssuerPattern;
        }

        // Update secret reference if provided
        if (oidcClientSecretRef != null && !oidcClientSecretRef.isBlank()) {
            if (!secretService.isValidFormat(oidcClientSecretRef)) {
                throw new IllegalArgumentException(
                    "Invalid secret reference format. Use encrypt:, aws-sm://, aws-ps://, gcp-sm://, or vault:// prefix");
            }
            config.oidcClientSecretRef = secretService.prepareForStorage(oidcClientSecretRef);
        }

        config.validateOidcConfig();
        config.updatedAt = Instant.now();
        repository.update(config);

        LOG.infof("Updated auth config for domain: %s (multiTenant: %s)", config.emailDomain, config.oidcMultiTenant);

        return config;
    }

    /**
     * Delete an auth config.
     */
    @Transactional
    public void delete(String id) {
        ClientAuthConfig config = repository.findByIdOptional(id)
            .orElseThrow(() -> new IllegalArgumentException("Auth config not found: " + id));

        repository.delete(config);
        LOG.infof("Deleted auth config for domain: %s", config.emailDomain);
    }

    /**
     * Validate that the secret reference is accessible.
     * This checks the reference can be resolved without returning the actual value.
     *
     * @param secretRef The secret reference to validate
     * @return ValidationResult indicating success or failure
     */
    public ValidationResult validateSecretReference(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return ValidationResult.failure("Secret reference is required");
        }

        return secretService.validate(secretRef);
    }

    /**
     * Resolve the OIDC client secret for a config.
     * This returns the plaintext secret for use in OIDC authentication.
     *
     * SECURITY: This method should only be called by system processes
     * that need the actual secret value (e.g., OIDC token exchange).
     * The calling code must ensure Super Admin authorization.
     *
     * @param config The auth config
     * @return Optional containing the plaintext secret if configured
     */
    public Optional<String> resolveClientSecret(ClientAuthConfig config) {
        if (config == null || !config.hasClientSecret()) {
            return Optional.empty();
        }

        return secretService.resolveOptional(config.oidcClientSecretRef);
    }

    /**
     * Get OIDC configuration with resolved secret.
     * Returns a DTO with the plaintext secret for use in OIDC flows.
     *
     * SECURITY: This method should only be called by system processes.
     * The calling code must ensure Super Admin authorization.
     */
    public Optional<OidcConfig> getOidcConfig(String emailDomain) {
        return findByEmailDomain(emailDomain)
            .filter(config -> config.authProvider == AuthProvider.OIDC)
            .map(config -> new OidcConfig(
                config.oidcIssuerUrl,
                config.oidcClientId,
                resolveClientSecret(config).orElse(null)
            ));
    }

    /**
     * DTO containing resolved OIDC configuration.
     */
    public record OidcConfig(
        String issuerUrl,
        String clientId,
        String clientSecret
    ) {}
}
