package tech.flowcatalyst.platform.authorization.operations;

import tech.flowcatalyst.platform.audit.Audited;

/**
 * Sealed interface for all Role operations.
 *
 * All operations on AuthRole must go through RoleAdminService.execute()
 * which enforces audit logging. This sealed interface ensures compile-time
 * exhaustive matching in the service's switch expression.
 */
public sealed interface RoleOperation extends Audited
    permits CreateRole, UpdateRole, DeleteRole, SyncRoles {
}
