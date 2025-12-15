package tech.flowcatalyst.dispatchjob.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tech.flowcatalyst.dispatch.DispatchMode;
import tech.flowcatalyst.dispatchjob.model.DispatchProtocol;

import java.time.Instant;
import java.util.Map;

public record CreateDispatchJobRequest(
    @JsonProperty("source")
    @NotBlank(message = "source is required")
    String source,

    @JsonProperty("type")
    @NotBlank(message = "type is required")
    String type,

    @JsonProperty("groupId")
    String groupId,

    @JsonProperty("metadata")
    Map<String, String> metadata,

    @JsonProperty("targetUrl")
    @NotBlank(message = "targetUrl is required")
    String targetUrl,

    @JsonProperty("protocol")
    DispatchProtocol protocol,

    @JsonProperty("headers")
    Map<String, String> headers,

    @JsonProperty("payload")
    @NotNull(message = "payload is required")
    String payload,

    @JsonProperty("payloadContentType")
    String payloadContentType,

    @JsonProperty("credentialsId")
    @NotNull(message = "credentialsId is required")
    String credentialsId,

    @JsonProperty("clientId")
    String clientId,

    @JsonProperty("subscriptionId")
    String subscriptionId,

    @JsonProperty("mode")
    DispatchMode mode,

    @JsonProperty("dispatchPoolId")
    String dispatchPoolId,

    @JsonProperty("sequence")
    Integer sequence,

    @JsonProperty("timeoutSeconds")
    Integer timeoutSeconds,

    @JsonProperty("schemaId")
    String schemaId,

    @JsonProperty("maxRetries")
    Integer maxRetries,

    @JsonProperty("retryStrategy")
    String retryStrategy,

    @JsonProperty("scheduledFor")
    Instant scheduledFor,

    @JsonProperty("expiresAt")
    Instant expiresAt,

    @JsonProperty("idempotencyKey")
    String idempotencyKey,

    @JsonProperty("externalId")
    String externalId,

    @JsonProperty("queueUrl")
    @NotBlank(message = "queueUrl is required")
    String queueUrl
) {
}
