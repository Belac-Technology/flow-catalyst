package tech.flowcatalyst.messagerouter.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QueueStats(
    @JsonProperty("name") String name,
    @JsonProperty("totalMessages") long totalMessages,
    @JsonProperty("totalConsumed") long totalConsumed,
    @JsonProperty("totalFailed") long totalFailed,
    @JsonProperty("successRate") double successRate,
    @JsonProperty("currentSize") long currentSize,
    @JsonProperty("throughput") double throughput,
    @JsonProperty("pendingMessages") long pendingMessages,
    @JsonProperty("messagesNotVisible") long messagesNotVisible
) {
    public static QueueStats empty(String name) {
        return new QueueStats(name, 0, 0, 0, 0.0, 0, 0.0, 0, 0);
    }
}
