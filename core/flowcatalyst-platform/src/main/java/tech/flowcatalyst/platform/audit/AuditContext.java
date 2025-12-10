package tech.flowcatalyst.platform.audit;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.platform.principal.Principal;
import tech.flowcatalyst.platform.principal.PrincipalRepository;
import tech.flowcatalyst.platform.principal.PrincipalType;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.util.Optional;

/**
 * Request-scoped context holding the current principal ID for audit logging.
 *
 * Can be populated:
 * - Automatically via AuditContextFilter (for HTTP requests)
 * - Manually via setPrincipalId() for tests
 * - Via setSystemPrincipal() for background jobs, CLI tools, and startup tasks
 *
 * The SYSTEM principal is a special service account used for automated operations
 * that occur outside of a user request context.
 */
@RequestScoped
public class AuditContext {

    public static final String SYSTEM_PRINCIPAL_CODE = "SYSTEM";
    public static final String SYSTEM_PRINCIPAL_NAME = "System";

    @Inject
    PrincipalRepository principalRepo;

    private String principalId;
    private String principalType; // "USER", "SERVICE", or "SYSTEM"

    /**
     * Set principal ID manually (for tests, background jobs, CLI tools).
     */
    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
        this.principalType = "USER";
    }

    /**
     * Set the context to use the SYSTEM principal for automated operations.
     * Creates the SYSTEM principal if it doesn't exist.
     */
    public void setSystemPrincipal() {
        Principal systemPrincipal = getOrCreateSystemPrincipal();
        this.principalId = systemPrincipal.id;
        this.principalType = "SYSTEM";
    }

    /**
     * Get the current principal ID, or null if not set.
     */
    public String getPrincipalId() {
        return principalId;
    }

    /**
     * Get the current principal type ("USER", "SERVICE", or "SYSTEM").
     */
    public String getPrincipalType() {
        return principalType;
    }

    /**
     * Get the current principal ID, throwing if not set.
     * Use this when audit context is required.
     */
    public String requirePrincipalId() {
        if (principalId == null) {
            throw new IllegalStateException("Audit context not set - operation rejected");
        }
        return principalId;
    }

    /**
     * Check if principal ID is set.
     */
    public boolean isSet() {
        return principalId != null;
    }

    /**
     * Check if this is the system principal.
     */
    public boolean isSystemPrincipal() {
        return "SYSTEM".equals(principalType);
    }

    /**
     * Get or create the SYSTEM service principal.
     */
    private Principal getOrCreateSystemPrincipal() {
        // Look for existing SYSTEM principal by service account code
        Optional<Principal> existing = principalRepo.findByServiceAccountCode(SYSTEM_PRINCIPAL_CODE);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Create the SYSTEM principal
        Principal system = new Principal();
        system.id = TsidGenerator.generate();
        system.type = PrincipalType.SERVICE;
        system.name = SYSTEM_PRINCIPAL_NAME;
        system.active = true;
        system.clientId = null; // Platform-level, no client

        system.serviceAccount = new tech.flowcatalyst.platform.principal.ServiceAccount();
        system.serviceAccount.code = SYSTEM_PRINCIPAL_CODE;
        system.serviceAccount.description = "System principal for automated operations (background jobs, startup tasks, CLI tools)";

        principalRepo.persist(system);
        return system;
    }
}
