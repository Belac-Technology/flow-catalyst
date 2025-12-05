package tech.flowcatalyst.platform.application.operations;

/**
 * Operation to update an existing Application.
 *
 * @param applicationId  The application to update
 * @param name           New name (null to keep existing)
 * @param description    New description (null to keep existing)
 * @param defaultBaseUrl New base URL (null to keep existing)
 * @param iconUrl        New icon URL (null to keep existing)
 */
public record UpdateApplication(
    Long applicationId,
    String name,
    String description,
    String defaultBaseUrl,
    String iconUrl
) implements ApplicationOperation {
}
