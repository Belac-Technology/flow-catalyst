package tech.flowcatalyst.platform.application.operations;

/**
 * Operation to deactivate an Application.
 *
 * @param applicationId The application to deactivate
 */
public record DeactivateApplication(
    Long applicationId
) implements ApplicationOperation {
}
