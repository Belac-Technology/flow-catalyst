package tech.flowcatalyst.platform.application.operations;

/**
 * Operation to delete an Application.
 * Can only delete applications that are deactivated and have no tenant configurations.
 *
 * @param applicationId The application to delete
 */
public record DeleteApplication(
    Long applicationId
) implements ApplicationOperation {
}
