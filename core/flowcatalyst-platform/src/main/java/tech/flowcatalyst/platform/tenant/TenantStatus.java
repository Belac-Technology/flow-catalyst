package tech.flowcatalyst.platform.tenant;

/**
 * Status of a tenant organization.
 * Stored as VARCHAR in database for future extensibility.
 */
public enum TenantStatus {
    /**
     * Tenant is active and operational
     */
    ACTIVE,

    /**
     * Tenant is inactive (see statusReason for details)
     */
    INACTIVE,

    /**
     * Tenant is suspended (temporarily disabled)
     */
    SUSPENDED
}
