package tech.flowcatalyst.dispatchjob.model;

public enum DispatchStatus {
    PENDING,        // Not yet attempted
    IN_PROGRESS,    // Currently being sent
    COMPLETED,      // Successfully delivered
    FAILED,         // Failed but will retry
    ERROR,          // Exhausted all retries
    CANCELLED       // Manually cancelled
}