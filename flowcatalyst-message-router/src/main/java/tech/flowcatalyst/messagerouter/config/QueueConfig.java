package tech.flowcatalyst.messagerouter.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QueueConfig(
    @JsonProperty("queueName") String queueName,
    @JsonProperty("queueUri") String queueUri
) {
}
