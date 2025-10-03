package tech.flowcatalyst.messagerouter.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProcessingPool(
    @JsonProperty("code") String code,
    @JsonProperty("concurrency") int concurrency
) {
}
