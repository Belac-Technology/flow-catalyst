package tech.flowcatalyst.platform.authorization.operations;

import java.util.Set;

/**
 * Operation to update an existing Role.
 *
 * @param roleName       Full role name (e.g., "platform:admin")
 * @param displayName    New display name (null to keep existing)
 * @param description    New description (null to keep existing)
 * @param permissions    New permissions (null to keep existing)
 * @param clientManaged  New clientManaged value (null to keep existing)
 */
public record UpdateRole(
    String roleName,
    String displayName,
    String description,
    Set<String> permissions,
    Boolean clientManaged
) implements RoleOperation {
}
