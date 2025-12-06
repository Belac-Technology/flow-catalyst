package tech.flowcatalyst.platform.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.flowcatalyst.platform.authorization.PermissionRegistry;
import tech.flowcatalyst.platform.authorization.PrincipalRoleRepository;
import tech.flowcatalyst.platform.authorization.PrincipalRole;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.shared.TsidGenerator;
import tech.flowcatalyst.platform.client.Client;
import tech.flowcatalyst.platform.client.ClientRepository;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for application management and access resolution.
 *
 * Application access is determined by roles:
 * - If a user has any role prefixed with the application code, they can access that app
 * - The client scope depends on user type:
 *   - Anchor users: roles apply to ALL clients
 *   - Partner users: roles apply to GRANTED clients only
 *   - Client users: roles apply to OWN client only
 */
@ApplicationScoped
public class ApplicationService {

    @Inject
    ApplicationRepository applicationRepo;

    @Inject
    ApplicationClientConfigRepository configRepo;

    @Inject
    PrincipalRepository principalRepo;

    @Inject
    PrincipalRoleRepository roleRepo;

    @Inject
    ClientRepository clientRepo;

    // ========================================================================
    // Application CRUD
    // ========================================================================

    /**
     * Create a new application.
     *
     * @param code Unique application code (used in role prefixes)
     * @param name Display name
     * @param description Optional description
     * @param defaultBaseUrl Default URL for the application
     * @return Created application
     * @throws BadRequestException if code already exists
     */
    public Application createApplication(String code, String name, String description, String defaultBaseUrl) {
        // Validate code format (lowercase alphanumeric with hyphens)
        if (!isValidCode(code)) {
            throw new BadRequestException("Invalid application code. Must be lowercase alphanumeric with hyphens only.");
        }

        // Check uniqueness
        if (applicationRepo.existsByCode(code)) {
            throw new BadRequestException("Application code already exists: " + code);
        }

        Application app = new Application();
        app.id = TsidGenerator.generate();
        app.code = code.toLowerCase();
        app.name = name;
        app.description = description;
        app.defaultBaseUrl = defaultBaseUrl;
        app.active = true;

        applicationRepo.persist(app);
        return app;
    }

    /**
     * Update an application.
     *
     * @param applicationId Application ID
     * @param name New name (or null to keep existing)
     * @param description New description (or null to keep existing)
     * @param defaultBaseUrl New base URL (or null to keep existing)
     * @param iconUrl New icon URL (or null to keep existing)
     * @return Updated application
     * @throws NotFoundException if application not found
     */
    public Application updateApplication(Long applicationId, String name, String description,
                                         String defaultBaseUrl, String iconUrl) {
        Application app = applicationRepo.findByIdOptional(applicationId)
            .orElseThrow(() -> new NotFoundException("Application not found"));

        if (name != null) app.name = name;
        if (description != null) app.description = description;
        if (defaultBaseUrl != null) app.defaultBaseUrl = defaultBaseUrl;
        if (iconUrl != null) app.iconUrl = iconUrl;

        applicationRepo.update(app);
        return app;
    }

    /**
     * Activate an application.
     */
    public void activateApplication(Long applicationId) {
        Application app = applicationRepo.findByIdOptional(applicationId)
            .orElseThrow(() -> new NotFoundException("Application not found"));
        app.active = true;
        applicationRepo.update(app);
    }

    /**
     * Deactivate an application.
     */
    public void deactivateApplication(Long applicationId) {
        Application app = applicationRepo.findByIdOptional(applicationId)
            .orElseThrow(() -> new NotFoundException("Application not found"));
        app.active = false;
        applicationRepo.update(app);
    }

    // ========================================================================
    // Client Configuration
    // ========================================================================

    /**
     * Configure an application for a specific client.
     *
     * @param applicationId Application ID
     * @param clientId Client ID
     * @param enabled Whether the app is enabled for this client
     * @param baseUrlOverride Optional URL override (e.g., client1.app.com)
     * @param config Optional additional configuration
     * @return Created or updated config
     */
    public ApplicationClientConfig configureForClient(Long applicationId, Long clientId,
                                                       boolean enabled, String baseUrlOverride,
                                                       Map<String, Object> config) {
        Application app = applicationRepo.findByIdOptional(applicationId)
            .orElseThrow(() -> new NotFoundException("Application not found"));

        Client client = clientRepo.findByIdOptional(clientId)
            .orElseThrow(() -> new NotFoundException("Client not found"));

        ApplicationClientConfig clientConfig = configRepo
            .findByApplicationAndClient(applicationId, clientId)
            .orElseGet(() -> {
                ApplicationClientConfig newConfig = new ApplicationClientConfig();
                newConfig.id = TsidGenerator.generate();
                newConfig.applicationId = app.id;
                newConfig.clientId = client.id;
                return newConfig;
            });

        clientConfig.enabled = enabled;
        clientConfig.baseUrlOverride = baseUrlOverride;
        clientConfig.configJson = config;

        if (configRepo.findByIdOptional(clientConfig.id).isPresent()) {
            configRepo.update(clientConfig);
        } else {
            configRepo.persist(clientConfig);
        }

        return clientConfig;
    }

    /**
     * Enable an application for a client.
     */
    public void enableForClient(Long applicationId, Long clientId) {
        configureForClient(applicationId, clientId, true, null, null);
    }

    /**
     * Disable an application for a client.
     */
    public void disableForClient(Long applicationId, Long clientId) {
        Optional<ApplicationClientConfig> config = configRepo.findByApplicationAndClient(applicationId, clientId);
        if (config.isPresent()) {
            config.get().enabled = false;
            configRepo.update(config.get());
        }
        // If no config exists, the app is not enabled anyway
    }

    /**
     * Get the effective URL for an application and client.
     *
     * @param applicationId Application ID
     * @param clientId Client ID
     * @return The effective URL (client override or default)
     */
    public String getEffectiveUrl(Long applicationId, Long clientId) {
        Application app = applicationRepo.findByIdOptional(applicationId)
            .orElseThrow(() -> new NotFoundException("Application not found"));

        Optional<ApplicationClientConfig> config = configRepo.findByApplicationAndClient(applicationId, clientId);
        if (config.isPresent() && config.get().baseUrlOverride != null && !config.get().baseUrlOverride.isBlank()) {
            return config.get().baseUrlOverride;
        }

        return app.defaultBaseUrl;
    }

    // ========================================================================
    // Access Resolution
    // ========================================================================

    /**
     * Get all applications accessible by a principal.
     * Application access is determined by having any role prefixed with the application code.
     *
     * @param principalId Principal ID
     * @return List of accessible applications
     */
    public List<Application> getAccessibleApplications(Long principalId) {
        // Get all roles for the principal
        List<PrincipalRole> principalRoles = roleRepo.findByPrincipalId(principalId);
        Set<String> roleStrings = principalRoles.stream()
            .map(pr -> pr.roleName)
            .collect(Collectors.toSet());

        // Extract application codes from roles
        Set<String> appCodes = PermissionRegistry.extractApplicationCodes(roleStrings);

        if (appCodes.isEmpty()) {
            return List.of();
        }

        // Find applications by codes
        return applicationRepo.findByCodes(appCodes);
    }

    /**
     * Get roles for a specific application that a principal has.
     *
     * @param principalId Principal ID
     * @param applicationCode Application code
     * @return Set of role strings for that application
     */
    public Set<String> getRolesForApplication(Long principalId, String applicationCode) {
        List<PrincipalRole> principalRoles = roleRepo.findByPrincipalId(principalId);
        Set<String> roleStrings = principalRoles.stream()
            .map(pr -> pr.roleName)
            .collect(Collectors.toSet());

        return PermissionRegistry.filterRolesForApplication(roleStrings, applicationCode);
    }

    /**
     * Check if a principal can access an application.
     *
     * @param principalId Principal ID
     * @param applicationCode Application code
     * @return true if the principal has any roles for this application
     */
    public boolean canAccessApplication(Long principalId, String applicationCode) {
        return !getRolesForApplication(principalId, applicationCode).isEmpty();
    }

    /**
     * Check if a principal can access an application for a specific client.
     * This considers:
     * 1. The principal must have roles for the application
     * 2. The application must be enabled for the client (if config exists)
     * 3. The principal must have access to the client (based on user type)
     *
     * @param principalId Principal ID
     * @param applicationCode Application code
     * @param clientId Client ID
     * @return true if access is allowed
     */
    public boolean canAccessApplicationForClient(Long principalId, String applicationCode, Long clientId) {
        // Check if principal has roles for this application
        if (!canAccessApplication(principalId, applicationCode)) {
            return false;
        }

        // Find the application
        Optional<Application> app = applicationRepo.findByCode(applicationCode);
        if (app.isEmpty() || !app.get().active) {
            return false;
        }

        // Check if application is enabled for client (if config exists and is disabled, deny)
        Optional<ApplicationClientConfig> config = configRepo.findByApplicationAndClient(app.get().id, clientId);
        if (config.isPresent() && !config.get().enabled) {
            return false;
        }

        // Client access is checked separately via ClientAccessService
        // This method only checks application-level access
        return true;
    }

    // ========================================================================
    // Query Methods
    // ========================================================================

    public Optional<Application> findById(Long id) {
        return applicationRepo.findByIdOptional(id);
    }

    public Optional<Application> findByCode(String code) {
        return applicationRepo.findByCode(code);
    }

    public List<Application> findAllActive() {
        return applicationRepo.findAllActive();
    }

    public List<Application> findAll() {
        return applicationRepo.listAll();
    }

    public List<ApplicationClientConfig> getConfigsForApplication(Long applicationId) {
        return configRepo.findByApplication(applicationId);
    }

    public List<ApplicationClientConfig> getConfigsForClient(Long clientId) {
        return configRepo.findByClient(clientId);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private boolean isValidCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        // Lowercase alphanumeric with hyphens, must start with letter
        return code.matches("^[a-z][a-z0-9-]*$");
    }
}
