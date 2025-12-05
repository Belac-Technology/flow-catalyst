package tech.flowcatalyst.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.flowcatalyst.platform.application.operations.*;
import tech.flowcatalyst.platform.audit.AuditContext;
import tech.flowcatalyst.platform.audit.AuditLog;
import tech.flowcatalyst.platform.audit.AuditLogRepository;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.application.ApplicationRepository;
import tech.flowcatalyst.platform.application.ApplicationClientConfigRepository;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Admin service for Application operations with enforced audit logging.
 *
 * All operations must go through the execute() method which:
 * 1. Validates the audit context is set (principal ID required)
 * 2. Executes the operation
 * 3. Writes the audit log entry
 *
 * This ensures no operation can be performed without audit logging.
 *
 * Note: This is the backend admin service. The platform module's ApplicationService
 * provides runtime access resolution for applications.
 */
@ApplicationScoped
public class ApplicationAdminService {

    private static final String ENTITY_TYPE = "Application";

    // Code format: lowercase alphanumeric with hyphens, starting with letter
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");

    @Inject
    ApplicationRepository repo;

    @Inject
    ApplicationClientConfigRepository configRepo;

    @Inject
    AuditLogRepository auditRepo;

    @Inject
    AuditContext auditContext;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Execute an Application operation with audit logging.
     *
     * @param operation The operation to execute
     * @return The affected Application (or null for delete)
     * @throws IllegalStateException if audit context is not set
     */
    @Transactional
    public Application execute(ApplicationOperation operation) {
        // 1. Validate principal is set - operations cannot proceed without audit context
        Long principalId = auditContext.requirePrincipalId();

        // 2. Execute the operation
        Application result = switch (operation) {
            case CreateApplication op -> doCreate(op);
            case UpdateApplication op -> doUpdate(op);
            case ActivateApplication op -> doActivate(op);
            case DeactivateApplication op -> doDeactivate(op);
            case DeleteApplication op -> doDelete(op);
        };

        // 3. Write audit log (always happens if we get here)
        Long entityId = result != null ? result.id : extractEntityId(operation);
        writeAuditLog(operation, entityId, principalId);

        return result;
    }

    // ========================================================================
    // Operation Implementations
    // ========================================================================

    private Application doCreate(CreateApplication op) {
        // Validate code format
        if (!isValidCode(op.code())) {
            throw new BadRequestException(
                "Invalid application code. Must be lowercase alphanumeric with hyphens, starting with a letter."
            );
        }

        // Check uniqueness
        if (repo.existsByCode(op.code())) {
            throw new BadRequestException("Application code already exists: " + op.code());
        }

        // Validate name
        if (op.name() == null || op.name().isBlank()) {
            throw new BadRequestException("Name is required");
        }
        if (op.name().length() > 255) {
            throw new BadRequestException("Name must be 255 characters or less");
        }

        // Validate description
        if (op.description() != null && op.description().length() > 1000) {
            throw new BadRequestException("Description must be 1000 characters or less");
        }

        Application app = new Application();
        app.id = TsidGenerator.generate();
        app.code = op.code().toLowerCase();
        app.name = op.name();
        app.description = op.description();
        app.defaultBaseUrl = op.defaultBaseUrl();
        app.iconUrl = op.iconUrl();
        app.active = true;

        repo.persist(app);
        return app;
    }

    private Application doUpdate(UpdateApplication op) {
        Application app = findOrThrow(op.applicationId());

        if (op.name() != null) {
            if (op.name().isBlank()) {
                throw new BadRequestException("Name cannot be blank");
            }
            if (op.name().length() > 255) {
                throw new BadRequestException("Name must be 255 characters or less");
            }
            app.name = op.name();
        }

        if (op.description() != null) {
            if (op.description().length() > 1000) {
                throw new BadRequestException("Description must be 1000 characters or less");
            }
            app.description = op.description();
        }

        if (op.defaultBaseUrl() != null) {
            app.defaultBaseUrl = op.defaultBaseUrl();
        }

        if (op.iconUrl() != null) {
            app.iconUrl = op.iconUrl();
        }

        return app;
    }

    private Application doActivate(ActivateApplication op) {
        Application app = findOrThrow(op.applicationId());

        if (app.active) {
            throw new BadRequestException("Application is already active");
        }

        app.active = true;
        return app;
    }

    private Application doDeactivate(DeactivateApplication op) {
        Application app = findOrThrow(op.applicationId());

        if (!app.active) {
            throw new BadRequestException("Application is already deactivated");
        }

        app.active = false;
        return app;
    }

    private Application doDelete(DeleteApplication op) {
        Application app = findOrThrow(op.applicationId());

        // Can only delete deactivated applications
        if (app.active) {
            throw new BadRequestException("Cannot delete active application. Deactivate it first.");
        }

        // Check for client configurations
        long configCount = configRepo.countByApplication(app.id);
        if (configCount > 0) {
            throw new BadRequestException(
                "Cannot delete application with client configurations. Remove all configurations first."
            );
        }

        repo.delete(app);
        return app;
    }

    // ========================================================================
    // Query Methods (no audit required for reads)
    // ========================================================================

    public Optional<Application> findById(Long id) {
        return repo.findByIdOptional(id);
    }

    public Optional<Application> findByCode(String code) {
        return repo.findByCode(code);
    }

    public List<Application> findAll() {
        return repo.listAll();
    }

    public List<Application> findAllActive() {
        return repo.findAllActive();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Application findOrThrow(Long id) {
        return repo.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Application not found: " + id));
    }

    private boolean isValidCode(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    private Long extractEntityId(ApplicationOperation operation) {
        return switch (operation) {
            case CreateApplication op -> null;
            case UpdateApplication op -> op.applicationId();
            case ActivateApplication op -> op.applicationId();
            case DeactivateApplication op -> op.applicationId();
            case DeleteApplication op -> op.applicationId();
        };
    }

    private void writeAuditLog(ApplicationOperation operation, Long entityId, Long principalId) {
        AuditLog log = new AuditLog();
        log.id = TsidGenerator.generate();
        log.entityType = ENTITY_TYPE;
        log.entityId = entityId;
        log.operation = operation.getClass().getSimpleName();
        log.operationJson = toJson(operation);
        log.principalId = principalId;
        log.performedAt = Instant.now();
        auditRepo.persist(log);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize operation to JSON", e);
        }
    }
}
