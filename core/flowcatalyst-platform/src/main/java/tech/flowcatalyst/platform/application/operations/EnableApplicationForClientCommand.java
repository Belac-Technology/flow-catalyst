package tech.flowcatalyst.platform.application.operations;

/**
 * Command to enable an application for a client.
 *
 * @param applicationId The application to enable
 * @param clientId The client to enable it for
 * @param baseUrlOverride Optional URL override for this client
 */
public record EnableApplicationForClientCommand(
    String applicationId,
    String clientId,
    String baseUrlOverride
) {}
