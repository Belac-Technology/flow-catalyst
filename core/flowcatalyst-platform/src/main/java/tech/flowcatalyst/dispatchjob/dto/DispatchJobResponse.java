package tech.flowcatalyst.dispatchjob.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.flowcatalyst.dispatchjob.entity.DispatchJob;
import tech.flowcatalyst.dispatchjob.model.DispatchProtocol;
import tech.flowcatalyst.dispatchjob.model.DispatchStatus;

import java.time.Instant;
import java.util.Map;

public record DispatchJobResponse(
    @JsonProperty("id") String id,
    @JsonProperty("externalId") String externalId,
    @JsonProperty("source") String source,
    @JsonProperty("type") String type,
    @JsonProperty("groupId") String groupId,
    @JsonProperty("metadata") Map<String, String> metadata,
    @JsonProperty("targetUrl") String targetUrl,
    @JsonProperty("protocol") DispatchProtocol protocol,
    @JsonProperty("headers") Map<String, String> headers,
    @JsonProperty("payloadContentType") String payloadContentType,
    @JsonProperty("credentialsId") String credentialsId,
    @JsonProperty("status") DispatchStatus status,
    @JsonProperty("maxRetries") Integer maxRetries,
    @JsonProperty("retryStrategy") String retryStrategy,
    @JsonProperty("scheduledFor") Instant scheduledFor,
    @JsonProperty("expiresAt") Instant expiresAt,
    @JsonProperty("attemptCount") Integer attemptCount,
    @JsonProperty("lastAttemptAt") Instant lastAttemptAt,
    @JsonProperty("completedAt") Instant completedAt,
    @JsonProperty("durationMillis") Long durationMillis,
    @JsonProperty("lastError") String lastError,
    @JsonProperty("idempotencyKey") String idempotencyKey,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("updatedAt") Instant updatedAt
) {
    public static DispatchJobResponse from(DispatchJob job) {
        // Convert metadata entities to Map
        Map<String, String> metadataMap = new java.util.HashMap<>();
        if (job.metadata != null) {
            job.metadata.forEach(m -> metadataMap.put(m.key, m.value));
        }

        return new DispatchJobResponse(
            job.id,
            job.externalId,
            job.source,
            job.type,
            job.groupId,
            metadataMap,
            job.targetUrl,
            job.protocol,
            job.headers,
            job.payloadContentType,
            job.credentialsId,
            job.status,
            job.maxRetries,
            job.retryStrategy,
            job.scheduledFor,
            job.expiresAt,
            job.attemptCount,
            job.lastAttemptAt,
            job.completedAt,
            job.durationMillis,
            job.lastError,
            job.idempotencyKey,
            job.createdAt,
            job.updatedAt
        );
    }
}
