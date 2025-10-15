package tech.flowcatalyst.messagerouter.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for a single queue
 *
 * @param queueName the queue name (for ActiveMQ)
 * @param queueUri the queue URI (for SQS)
 * @param connections number of consumer connections for this queue (optional, defaults to global config)
 */
public record QueueConfig(
    @JsonProperty("queueName") String queueName,
    @JsonProperty("queueUri") String queueUri,
    @JsonProperty("connections") Integer connections
) {
}
