package tech.flowcatalyst.platform.application.operations.createapplication;

import tech.flowcatalyst.platform.application.Application;

/**
 * Command to create a new Application.
 *
 * @param code           Unique application code (used in role prefixes, e.g., "tms", "wms")
 * @param name           Display name (e.g., "Transport Management System")
 * @param description    Optional description
 * @param defaultBaseUrl Optional default URL for the application
 * @param iconUrl        Optional icon URL
 * @param type           Application type (APPLICATION or INTEGRATION), defaults to APPLICATION
 * @param provisionServiceAccount Whether to create a service account for this application
 */
public record CreateApplicationCommand(
    String code,
    String name,
    String description,
    String defaultBaseUrl,
    String iconUrl,
    Application.ApplicationType type,
    boolean provisionServiceAccount
) {
    /**
     * Constructor with defaults for backwards compatibility.
     */
    public CreateApplicationCommand(String code, String name, String description, String defaultBaseUrl, String iconUrl) {
        this(code, name, description, defaultBaseUrl, iconUrl, Application.ApplicationType.APPLICATION, true);
    }
}
