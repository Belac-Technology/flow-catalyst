package tech.flowcatalyst.serviceaccount.operations.createserviceaccount;

/**
 * Command to create a new service account.
 *
 * @param code        Unique identifier code (e.g., "tms-service")
 * @param name        Human-readable display name
 * @param description Optional description of the service account's purpose
 * @param clientId    Optional client ID for multi-tenant scoping
 * @param applicationId Optional application ID if created for an application
 */
public record CreateServiceAccountCommand(
    String code,
    String name,
    String description,
    String clientId,
    String applicationId
) {}
