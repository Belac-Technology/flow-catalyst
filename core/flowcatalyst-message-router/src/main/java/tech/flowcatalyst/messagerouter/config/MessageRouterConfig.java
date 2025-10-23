package tech.flowcatalyst.messagerouter.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MessageRouterConfig(
    @JsonProperty("queues") List<QueueConfig> queues,
    @JsonProperty("connections") int connections,
    @JsonProperty("processingPools") List<ProcessingPool> processingPools
) {
}
