package tech.flowcatalyst.platform.application.operations.updateapplication;

/**
 * Command to update an Application.
 *
 * @param applicationId  The ID of the application to update
 * @param name           New name (null to keep existing)
 * @param description    New description (null to keep existing)
 * @param defaultBaseUrl New base URL (null to keep existing)
 * @param iconUrl        New icon URL (null to keep existing)
 */
public record UpdateApplicationCommand(
    String applicationId,
    String name,
    String description,
    String defaultBaseUrl,
    String iconUrl
) {}
