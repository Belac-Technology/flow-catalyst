package tech.flowcatalyst.platform.application.operations;

import tech.flowcatalyst.platform.audit.Audited;

/**
 * Sealed interface for all Application operations.
 *
 * All operations on Application must go through ApplicationService.execute()
 * which enforces audit logging. This sealed interface ensures compile-time
 * exhaustive matching in the service's switch expression.
 */
public sealed interface ApplicationOperation extends Audited
    permits CreateApplication, UpdateApplication, ActivateApplication,
            DeactivateApplication, DeleteApplication {
}
