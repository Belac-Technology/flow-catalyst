package tech.flowcatalyst.messagerouter.endpoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Request to seed test messages to queues")
public record SeedMessageRequest(

    @Schema(description = "Number of messages to send (WARNING: >100 to localhost may cause deadlock)",
            defaultValue = "10",
            examples = {"10", "50", "1000"})
    @JsonProperty(defaultValue = "10")
    Integer count,

    @Schema(description = "Target queue: 'high', 'medium', 'low', 'random', or full queue name like 'flow-catalyst-high-priority.fifo'",
            defaultValue = "random",
            examples = {"high", "medium", "low", "random"})
    @JsonProperty(defaultValue = "random")
    String queue,

    @Schema(description = "Target endpoint: 'fast', 'slow', 'faulty', 'fail', 'random', or full URL like 'https://httpbin.org/post'",
            defaultValue = "random",
            examples = {"fast", "slow", "faulty", "fail", "random"})
    @JsonProperty(defaultValue = "random")
    String endpoint
) {

    public SeedMessageRequest {
        // Provide defaults for null values
        count = count != null ? count : 10;
        queue = queue != null && !queue.isBlank() ? queue : "random";
        endpoint = endpoint != null && !endpoint.isBlank() ? endpoint : "random";
    }
}
