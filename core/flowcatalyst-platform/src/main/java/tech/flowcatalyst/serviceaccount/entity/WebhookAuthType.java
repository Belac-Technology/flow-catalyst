package tech.flowcatalyst.serviceaccount.entity;

/**
 * Authentication type for webhook credentials.
 */
public enum WebhookAuthType {
    /**
     * Bearer token authentication.
     * Authorization header: Bearer {token}
     */
    BEARER,

    /**
     * Basic authentication.
     * Authorization header: Basic {base64-encoded-string}
     */
    BASIC
}
