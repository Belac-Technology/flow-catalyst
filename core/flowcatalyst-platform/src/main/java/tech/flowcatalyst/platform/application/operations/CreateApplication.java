package tech.flowcatalyst.platform.application.operations;

/**
 * Operation to create a new Application.
 *
 * @param code           Unique application code (used in role prefixes, e.g., "tms", "wms")
 * @param name           Display name (e.g., "Transport Management System")
 * @param description    Optional description
 * @param defaultBaseUrl Optional default URL for the application
 * @param iconUrl        Optional icon URL
 */
public record CreateApplication(
    String code,
    String name,
    String description,
    String defaultBaseUrl,
    String iconUrl
) implements ApplicationOperation {
}
