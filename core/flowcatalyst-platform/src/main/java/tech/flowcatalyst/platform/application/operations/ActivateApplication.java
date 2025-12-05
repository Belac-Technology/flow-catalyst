package tech.flowcatalyst.platform.application.operations;

/**
 * Operation to activate an Application.
 *
 * @param applicationId The application to activate
 */
public record ActivateApplication(
    Long applicationId
) implements ApplicationOperation {
}
