package tech.flowcatalyst.dispatchjob.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import tech.flowcatalyst.dispatchjob.model.SignatureAlgorithm;

public record CreateCredentialsRequest(
    @JsonProperty("bearerToken")
    @NotBlank(message = "bearerToken is required")
    String bearerToken,

    @JsonProperty("signingSecret")
    @NotBlank(message = "signingSecret is required")
    String signingSecret,

    @JsonProperty("algorithm")
    SignatureAlgorithm algorithm
) {
}
