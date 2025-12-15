package tech.flowcatalyst.serviceaccount.operations.updateserviceaccount;

/**
 * Command to update a service account's metadata.
 *
 * @param serviceAccountId The service account ID to update
 * @param name             New display name (optional, null = no change)
 * @param description      New description (optional, null = no change)
 */
public record UpdateServiceAccountCommand(
    String serviceAccountId,
    String name,
    String description
) {}
