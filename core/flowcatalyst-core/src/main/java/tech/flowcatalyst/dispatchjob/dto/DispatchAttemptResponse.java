package tech.flowcatalyst.dispatchjob.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.flowcatalyst.dispatchjob.entity.DispatchAttempt;
import tech.flowcatalyst.dispatchjob.model.DispatchAttemptStatus;

import java.time.Instant;

public record DispatchAttemptResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("attemptNumber") Integer attemptNumber,
    @JsonProperty("attemptedAt") Instant attemptedAt,
    @JsonProperty("completedAt") Instant completedAt,
    @JsonProperty("durationMillis") Long durationMillis,
    @JsonProperty("status") DispatchAttemptStatus status,
    @JsonProperty("responseCode") Integer responseCode,
    @JsonProperty("responseBody") String responseBody,
    @JsonProperty("errorMessage") String errorMessage,
    @JsonProperty("createdAt") Instant createdAt
) {
    public static DispatchAttemptResponse from(DispatchAttempt attempt) {
        return new DispatchAttemptResponse(
            attempt.id,
            attempt.attemptNumber,
            attempt.attemptedAt,
            attempt.completedAt,
            attempt.durationMillis,
            attempt.status,
            attempt.responseCode,
            attempt.responseBody,
            attempt.errorMessage,
            attempt.createdAt
        );
    }
}
