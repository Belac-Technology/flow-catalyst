package tech.flowcatalyst.platform.authorization.operations;

/**
 * Operation to delete a Role.
 * Only DATABASE and SDK sourced roles can be deleted.
 *
 * @param roleName Full role name (e.g., "platform:admin")
 */
public record DeleteRole(
    String roleName
) implements RoleOperation {
}
