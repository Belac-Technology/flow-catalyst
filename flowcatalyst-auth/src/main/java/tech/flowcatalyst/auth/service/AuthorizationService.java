package tech.flowcatalyst.auth.service;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Service for authorization operations.
 * Simple RBAC permission checks only - business logic handles tenant isolation.
 */
@ApplicationScoped
public class AuthorizationService {

    // TODO: Implement authorization logic
    // - requirePermission(resource, action)
    // - hasPermission(resource, action)
    // - getRoles(principalId)
    // - getPermissions(principalId)
}
