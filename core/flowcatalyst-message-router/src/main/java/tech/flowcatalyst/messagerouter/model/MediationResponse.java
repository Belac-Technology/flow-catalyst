package tech.flowcatalyst.messagerouter.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Response from a mediation endpoint indicating whether the message should be acknowledged.
 *
 * <p>The endpoint returns HTTP 200 with this DTO to indicate:
 * <ul>
 *   <li><b>ack: true</b> - Message processing is complete, ACK it and mark as success</li>
 *   <li><b>ack: false</b> - Message is accepted but not ready to be processed yet (e.g., notBefore time not reached)
 *       Nack it and retry via queue visibility timeout</li>
 * </ul>
 */
@Schema(description = "Response from mediation endpoint indicating acknowledgment status")
public record MediationResponse(
    @JsonProperty("ack")
    @Schema(description = "Whether the message should be acknowledged (true) or nacked for retry (false)",
            examples = {"true", "false"})
    boolean ack,

    @JsonProperty("message")
    @Schema(description = "Optional message or reason (e.g., delay reason if ack=false)",
            examples = {"", "notBefore time not reached", "Processing scheduled for later"})
    String message
) {
    // Allow construction with just ack, defaulting message to empty string
    public MediationResponse(boolean ack) {
        this(ack, "");
    }
}
