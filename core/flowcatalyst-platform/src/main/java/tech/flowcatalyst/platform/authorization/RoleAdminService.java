package tech.flowcatalyst.platform.authorization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.flowcatalyst.platform.application.Application;
import tech.flowcatalyst.platform.application.ApplicationRepository;
import tech.flowcatalyst.platform.audit.AuditContext;
import tech.flowcatalyst.platform.audit.AuditLog;
import tech.flowcatalyst.platform.audit.AuditLogRepository;
import tech.flowcatalyst.platform.authorization.operations.*;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Admin service for Role operations with enforced audit logging.
 *
 * All operations must go through the execute() method which:
 * 1. Validates the audit context is set (principal ID required)
 * 2. Executes the operation
 * 3. Writes the audit log entry
 *
 * This ensures no operation can be performed without audit logging.
 */
@ApplicationScoped
public class RoleAdminService {

    private static final String ENTITY_TYPE = "AuthRole";

    @Inject
    AuthRoleRepository roleRepo;

    @Inject
    ApplicationRepository appRepo;

    @Inject
    PermissionRegistry permissionRegistry;

    @Inject
    AuditLogRepository auditRepo;

    @Inject
    AuditContext auditContext;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Execute a Role operation with audit logging.
     *
     * @param operation The operation to execute
     * @return The affected AuthRole (or null for delete/sync)
     * @throws IllegalStateException if audit context is not set
     */
    public AuthRole execute(RoleOperation operation) {
        // 1. Validate principal is set - operations cannot proceed without audit context
        Long principalId = auditContext.requirePrincipalId();

        // 2. Execute the operation
        AuthRole result = switch (operation) {
            case CreateRole op -> doCreate(op);
            case UpdateRole op -> doUpdate(op);
            case DeleteRole op -> doDelete(op);
            case SyncRoles op -> doSync(op);
        };

        // 3. Write audit log (always happens if we get here)
        Long entityId = result != null ? result.id : extractEntityId(operation);
        writeAuditLog(operation, entityId, principalId);

        return result;
    }

    // ========================================================================
    // Operation Implementations
    // ========================================================================

    private AuthRole doCreate(CreateRole op) {
        // Validate application exists
        Application app = appRepo.findByIdOptional(op.applicationId())
            .orElseThrow(() -> new NotFoundException("Application not found: " + op.applicationId()));

        // Validate name
        if (op.name() == null || op.name().isBlank()) {
            throw new BadRequestException("Role name is required");
        }

        // Construct full role name with app prefix
        String fullRoleName = app.code + ":" + op.name();

        // Check uniqueness
        if (roleRepo.existsByName(fullRoleName)) {
            throw new BadRequestException("Role already exists: " + fullRoleName);
        }

        // Create role
        AuthRole role = new AuthRole();
        role.id = TsidGenerator.generate();
        role.applicationId = app.id;
        role.applicationCode = app.code;
        role.name = fullRoleName;
        role.displayName = op.displayName() != null ? op.displayName() : formatDisplayName(op.name());
        role.description = op.description();
        role.permissions = op.permissions() != null ? op.permissions() : new HashSet<>();
        role.source = op.source() != null ? op.source() : AuthRole.RoleSource.DATABASE;
        role.clientManaged = op.clientManaged();

        roleRepo.persist(role);

        // Register into PermissionRegistry for runtime use
        permissionRegistry.registerRoleDynamic(fullRoleName, role.permissions, role.description);

        return role;
    }

    private AuthRole doUpdate(UpdateRole op) {
        AuthRole role = roleRepo.findByName(op.roleName())
            .orElseThrow(() -> new NotFoundException("Role not found: " + op.roleName()));

        if (role.source == AuthRole.RoleSource.CODE) {
            // CODE roles can only have clientManaged updated
            if (op.clientManaged() != null) {
                role.clientManaged = op.clientManaged();
            }
            // Ignore other fields for CODE roles
        } else {
            // DATABASE and SDK roles can be fully updated
            if (op.displayName() != null) {
                role.displayName = op.displayName();
            }
            if (op.description() != null) {
                role.description = op.description();
            }
            if (op.permissions() != null) {
                role.permissions = op.permissions();
                // Update registry
                permissionRegistry.registerRoleDynamic(role.name, role.permissions, role.description);
            }
            if (op.clientManaged() != null) {
                role.clientManaged = op.clientManaged();
            }
        }

        roleRepo.update(role);
        return role;
    }

    private AuthRole doDelete(DeleteRole op) {
        AuthRole role = roleRepo.findByName(op.roleName())
            .orElseThrow(() -> new NotFoundException("Role not found: " + op.roleName()));

        if (role.source == AuthRole.RoleSource.CODE) {
            throw new BadRequestException("Cannot delete CODE-defined role: " + op.roleName());
        }

        // Unregister from PermissionRegistry
        permissionRegistry.unregisterRole(role.name);

        roleRepo.delete(role);
        return role;
    }

    private AuthRole doSync(SyncRoles op) {
        Application app = appRepo.findByIdOptional(op.applicationId())
            .orElseThrow(() -> new NotFoundException("Application not found: " + op.applicationId()));

        Set<String> syncedRoleNames = new HashSet<>();

        for (SyncRoles.SyncRoleItem item : op.roles()) {
            String fullRoleName = app.code + ":" + item.name();
            syncedRoleNames.add(fullRoleName);

            Optional<AuthRole> existingOpt = roleRepo.findByName(fullRoleName);

            if (existingOpt.isPresent()) {
                AuthRole existing = existingOpt.get();
                if (existing.source == AuthRole.RoleSource.SDK) {
                    // Update existing SDK role
                    existing.displayName = item.displayName() != null ?
                        item.displayName() : formatDisplayName(item.name());
                    existing.description = item.description();
                    existing.permissions = item.permissions() != null ?
                        item.permissions() : new HashSet<>();
                    existing.clientManaged = item.clientManaged();

                    roleRepo.update(existing);
                    permissionRegistry.registerRoleDynamic(fullRoleName, existing.permissions, existing.description);
                }
                // Don't update CODE or DATABASE roles from SDK sync
            } else {
                // Create new SDK role
                AuthRole role = new AuthRole();
                role.id = TsidGenerator.generate();
                role.applicationId = app.id;
                role.applicationCode = app.code;
                role.name = fullRoleName;
                role.displayName = item.displayName() != null ? item.displayName() : formatDisplayName(item.name());
                role.description = item.description();
                role.permissions = item.permissions() != null ? item.permissions() : new HashSet<>();
                role.source = AuthRole.RoleSource.SDK;
                role.clientManaged = item.clientManaged();

                roleRepo.persist(role);
                permissionRegistry.registerRoleDynamic(fullRoleName, role.permissions, role.description);
            }
        }

        if (op.removeUnlisted()) {
            // Remove SDK roles that weren't in the sync list
            List<AuthRole> existingRoles = roleRepo.findByApplicationCode(app.code);
            for (AuthRole existing : existingRoles) {
                if (existing.source == AuthRole.RoleSource.SDK && !syncedRoleNames.contains(existing.name)) {
                    permissionRegistry.unregisterRole(existing.name);
                    roleRepo.delete(existing);
                }
            }
        }

        // Return null for sync operations (multiple entities affected)
        return null;
    }

    // ========================================================================
    // Query Methods (no audit required for reads)
    // ========================================================================

    public Optional<AuthRole> findByName(String name) {
        return roleRepo.findByName(name);
    }

    public List<AuthRole> findAll() {
        return roleRepo.listAll();
    }

    public List<AuthRole> findByApplicationCode(String applicationCode) {
        return roleRepo.findByApplicationCode(applicationCode);
    }

    public List<AuthRole> findBySource(AuthRole.RoleSource source) {
        return roleRepo.findBySource(source);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String formatDisplayName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return roleName;
        }
        String[] parts = roleName.split("-");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.toString();
    }

    private Long extractEntityId(RoleOperation operation) {
        return switch (operation) {
            case CreateRole op -> null;
            case UpdateRole op -> roleRepo.findByName(op.roleName()).map(r -> r.id).orElse(null);
            case DeleteRole op -> roleRepo.findByName(op.roleName()).map(r -> r.id).orElse(null);
            case SyncRoles op -> op.applicationId(); // Use app ID for sync operations
        };
    }

    private void writeAuditLog(RoleOperation operation, Long entityId, Long principalId) {
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
