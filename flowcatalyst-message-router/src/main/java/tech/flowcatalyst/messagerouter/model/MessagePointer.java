package tech.flowcatalyst.messagerouter.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessagePointer(
    @JsonProperty("id") String id,
    @JsonProperty("poolCode") String poolCode,
    @JsonProperty("authToken") String authToken,
    @JsonProperty("mediationType") MediationType mediationType,
    @JsonProperty("mediationTarget") String mediationTarget
) {
}
