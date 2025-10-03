package tech.flowcatalyst.messagerouter.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Statistics for a message queue")
public record QueueStats(
    @Schema(description = "Queue name or identifier", examples = {"flow-catalyst-high-priority.fifo", "flow-catalyst-medium-priority.fifo"})
    @JsonProperty("name") String name,

    @Schema(description = "Total number of messages received", examples = {"150000", "0", "5000"})
    @JsonProperty("totalMessages") long totalMessages,

    @Schema(description = "Total number of messages consumed", examples = {"149950", "0", "4995"})
    @JsonProperty("totalConsumed") long totalConsumed,

    @Schema(description = "Total number of failed messages", examples = {"50", "0", "5"})
    @JsonProperty("totalFailed") long totalFailed,

    @Schema(description = "Success rate (0.0 to 1.0)", examples = {"0.9996666666666667", "1.0", "0.999"})
    @JsonProperty("successRate") double successRate,

    @Schema(description = "Current queue depth/size", examples = {"500", "0", "150"})
    @JsonProperty("currentSize") long currentSize,

    @Schema(description = "Throughput in messages per second", examples = {"25.5", "0.0", "10.2"})
    @JsonProperty("throughput") double throughput,

    @Schema(description = "Number of pending messages in queue", examples = {"500", "0", "150"})
    @JsonProperty("pendingMessages") long pendingMessages,

    @Schema(description = "Number of messages currently being processed (not visible)", examples = {"10", "0", "5"})
    @JsonProperty("messagesNotVisible") long messagesNotVisible
) {
    public static QueueStats empty(String name) {
        return new QueueStats(name, 0, 0, 0, 0.0, 0, 0.0, 0, 0);
    }
}
