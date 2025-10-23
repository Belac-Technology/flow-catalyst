package tech.flowcatalyst.messagerouter.model;

public enum MediationResult {
    SUCCESS,              // 200 OK
    ERROR_CONNECTION,     // Connection timeout, refused, etc
    ERROR_SERVER,         // 500-599 status codes
    ERROR_PROCESS,        // 400 Bad Request (potentially transient)
    ERROR_CONFIG          // 401-499 (except 400) - auth/config errors, should ACK and not retry
}
