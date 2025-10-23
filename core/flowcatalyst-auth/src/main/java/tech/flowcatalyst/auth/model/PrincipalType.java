package tech.flowcatalyst.auth.model;

/**
 * Type of principal in the system.
 */
public enum PrincipalType {
    /**
     * Human user account
     */
    USER,

    /**
     * Service account for machine-to-machine authentication
     */
    SERVICE
}
