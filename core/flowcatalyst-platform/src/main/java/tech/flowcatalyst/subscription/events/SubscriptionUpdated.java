package tech.flowcatalyst.subscription.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.dispatch.DispatchMode;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;
import tech.flowcatalyst.subscription.*;

import java.time.Instant;
import java.util.List;

/**
 * Event emitted when a subscription is updated.
 *
 * <p>Event type: {@code platform:control-plane:subscription:updated}
 */
public record SubscriptionUpdated(
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
    String name,
    String description,
    String clientId,
    String clientIdentifier,
    List<EventTypeBinding> eventTypes,
    String target,
    String queue,
    List<ConfigEntry> customConfig,
    SubscriptionStatus status,
    int maxAgeSeconds,
    String dispatchPoolId,
    String dispatchPoolCode,
    int delaySeconds,
    int sequence,
    DispatchMode mode,
    int timeoutSeconds,
    int maxRetries,
    String serviceAccountId,
    boolean dataOnly
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:subscription:updated";
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
                subscriptionId, code, name, description, clientId, clientIdentifier,
                eventTypes, target, queue, customConfig, status,
                maxAgeSeconds, dispatchPoolId, dispatchPoolCode,
                delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String subscriptionId,
        String code,
        String name,
        String description,
        String clientId,
        String clientIdentifier,
        List<EventTypeBinding> eventTypes,
        String target,
        String queue,
        List<ConfigEntry> customConfig,
        SubscriptionStatus status,
        int maxAgeSeconds,
        String dispatchPoolId,
        String dispatchPoolCode,
        int delaySeconds,
        int sequence,
        DispatchMode mode,
        int timeoutSeconds,
        int maxRetries,
        String serviceAccountId,
        boolean dataOnly
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
        private String name;
        private String description;
        private String clientId;
        private String clientIdentifier;
        private List<EventTypeBinding> eventTypes;
        private String target;
        private String queue;
        private List<ConfigEntry> customConfig;
        private SubscriptionStatus status;
        private int maxAgeSeconds;
        private String dispatchPoolId;
        private String dispatchPoolCode;
        private int delaySeconds;
        private int sequence;
        private DispatchMode mode;
        private int timeoutSeconds;
        private int maxRetries;
        private String serviceAccountId;
        private boolean dataOnly;

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

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
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

        public Builder target(String target) {
            this.target = target;
            return this;
        }

        public Builder queue(String queue) {
            this.queue = queue;
            return this;
        }

        public Builder customConfig(List<ConfigEntry> customConfig) {
            this.customConfig = customConfig;
            return this;
        }

        public Builder status(SubscriptionStatus status) {
            this.status = status;
            return this;
        }

        public Builder maxAgeSeconds(int maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
            return this;
        }

        public Builder dispatchPoolId(String dispatchPoolId) {
            this.dispatchPoolId = dispatchPoolId;
            return this;
        }

        public Builder dispatchPoolCode(String dispatchPoolCode) {
            this.dispatchPoolCode = dispatchPoolCode;
            return this;
        }

        public Builder delaySeconds(int delaySeconds) {
            this.delaySeconds = delaySeconds;
            return this;
        }

        public Builder sequence(int sequence) {
            this.sequence = sequence;
            return this;
        }

        public Builder mode(DispatchMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder serviceAccountId(String serviceAccountId) {
            this.serviceAccountId = serviceAccountId;
            return this;
        }

        public Builder dataOnly(boolean dataOnly) {
            this.dataOnly = dataOnly;
            return this;
        }

        public SubscriptionUpdated build() {
            return new SubscriptionUpdated(
                eventId, time, executionId, correlationId, causationId, principalId,
                subscriptionId, code, name, description, clientId, clientIdentifier,
                eventTypes, target, queue, customConfig, status,
                maxAgeSeconds, dispatchPoolId, dispatchPoolCode,
                delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly
            );
        }
    }
}
