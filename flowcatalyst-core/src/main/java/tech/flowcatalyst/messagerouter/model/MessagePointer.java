package tech.flowcatalyst.messagerouter.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessagePointer(
    @JsonProperty("id") String id,
    @JsonProperty("poolCode") String poolCode,
    @JsonProperty("rateLimitPerMinute") Integer rateLimitPerMinute,
    @JsonProperty("rateLimitKey") String rateLimitKey,
    @JsonProperty("authToken") String authToken,
    @JsonProperty("mediationType") String mediationType,
    @JsonProperty("mediationTarget") String mediationTarget
) {
}
