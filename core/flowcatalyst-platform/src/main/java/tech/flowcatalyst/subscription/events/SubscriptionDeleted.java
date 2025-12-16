package tech.flowcatalyst.subscription.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import tech.flowcatalyst.subscription.EventTypeBinding;

import java.time.Instant;
import java.util.List;

/**
 * Event emitted when a subscription is deleted.
 *
 * <p>Event type: {@code platform:control-plane:subscription:deleted}
 */
public record SubscriptionDeleted(
    // Event metadata
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,

    // Event-specific payload
    String subscriptionId,
    String code,
    String clientId,
    String clientIdentifier,
    List<EventTypeBinding> eventTypes
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:subscription:deleted";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE_NAME = "platform:control-plane";

    @JsonIgnore
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    @JsonIgnore
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    @JsonIgnore
    public String specVersion() {
        return SPEC_VERSION;
    }

    @Override
    @JsonIgnore
    public String source() {
        return SOURCE_NAME;
    }

    @Override
    @JsonIgnore
    public String subject() {
        return "platform.subscription." + subscriptionId;
    }

    @Override
    @JsonIgnore
    public String messageGroup() {
        return "platform:subscription:" + (clientId != null ? clientId : "anchor");
    }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(
                subscriptionId, code, clientId, clientIdentifier, eventTypes
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String subscriptionId,
        String code,
        String clientId,
        String clientIdentifier,
        List<EventTypeBinding> eventTypes
    ) {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String eventId;
        private Instant time;
        private String executionId;
        private String correlationId;
        private String causationId;
        private String principalId;
        private String subscriptionId;
        private String code;
        private String clientId;
        private String clientIdentifier;
        private List<EventTypeBinding> eventTypes;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder subscriptionId(String subscriptionId) {
            this.subscriptionId = subscriptionId;
            return this;
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder clientIdentifier(String clientIdentifier) {
            this.clientIdentifier = clientIdentifier;
            return this;
        }

        public Builder eventTypes(List<EventTypeBinding> eventTypes) {
            this.eventTypes = eventTypes;
            return this;
        }

        public SubscriptionDeleted build() {
            return new SubscriptionDeleted(
                eventId, time, executionId, correlationId, causationId, principalId,
                subscriptionId, code, clientId, clientIdentifier, eventTypes
            );
        }
    }
}
