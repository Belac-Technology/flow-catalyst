package tech.flowcatalyst.dispatchjob.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.flowcatalyst.dispatchjob.entity.DispatchCredentials;
import tech.flowcatalyst.dispatchjob.model.SignatureAlgorithm;

import java.time.Instant;

public record CredentialsResponse(
    @JsonProperty("id") Long id,
    @JsonProperty("algorithm") SignatureAlgorithm algorithm,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("updatedAt") Instant updatedAt
) {
    public static CredentialsResponse from(DispatchCredentials credentials) {
        return new CredentialsResponse(
            credentials.id,
            credentials.algorithm,
            credentials.createdAt,
            credentials.updatedAt
        );
    }
}
