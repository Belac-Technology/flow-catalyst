package tech.flowcatalyst.messagerouter.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Represents a message currently in flight (being processed)
 */
@Schema(description = "A message currently being processed by the message router")
public record InFlightMessage(
    @Schema(description = "Message ID", example = "01K9XYM11VFTAPJEPJBR8070FY")
    @JsonProperty("messageId") String messageId,

    @Schema(description = "Queue identifier", example = "FC-staging-order-queue.fifo")
    @JsonProperty("queueId") String queueId,

    @Schema(description = "Timestamp when message entered the pipeline", example = "2025-11-13T06:30:00Z")
    @JsonProperty("addedToInPipelineAt") Instant addedToInPipelineAt,

    @Schema(description = "How long the message has been in flight (milliseconds)", example = "5000")
    @JsonProperty("elapsedTimeMs") long elapsedTimeMs,

    @Schema(description = "Pool code this message is being processed by", example = "staging-order-CONCURRENCY-10")
    @JsonProperty("poolCode") String poolCode
) {
    /**
     * Creates an InFlightMessage from a message ID and timestamp
     */
    public static InFlightMessage from(String messageId, String queueId, long addedAt, String poolCode) {
        long now = System.currentTimeMillis();
        long elapsedMs = now - addedAt;
        return new InFlightMessage(
            messageId,
            queueId,
            Instant.ofEpochMilli(addedAt),
            elapsedMs,
            poolCode
        );
    }
}
