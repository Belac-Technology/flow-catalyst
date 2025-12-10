package tech.flowcatalyst.dispatchjob.entity;

import tech.flowcatalyst.dispatchjob.model.DispatchAttemptStatus;

import java.time.Instant;

/**
 * Delivery attempt record embedded in DispatchJob documents.
 * Not a separate collection - embedded for single-document atomicity.
 */
public class DispatchAttempt {

    public String id;
    public Integer attemptNumber;
    public Instant attemptedAt;
    public Instant completedAt;
    public Long durationMillis;
    public DispatchAttemptStatus status;
    public Integer responseCode;
    public String responseBody;
    public String errorMessage;
    public String errorStackTrace;
    public Instant createdAt;

    public DispatchAttempt() {
    }
}
