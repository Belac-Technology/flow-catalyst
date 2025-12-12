package tech.flowcatalyst.dispatchpool.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.dispatchpool.DispatchPoolStatus;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * Event emitted when a dispatch pool is updated.
 *
 * <p>Event type: {@code platform:control-plane:dispatch-pool:updated}
 */
public record DispatchPoolUpdated(
    // Event metadata
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,

    // Event-specific payload
    String poolId,
    String code,
    String name,
    String description,
    int rateLimit,
    int concurrency,
    String applicationId,
    String applicationCode,
    String clientId,
    String clientIdentifier,
    DispatchPoolStatus status
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:dispatch-pool:updated";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE = "platform:control-plane";

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
        return SOURCE;
    }

    @Override
    @JsonIgnore
    public String subject() {
        return "platform.dispatch-pool." + poolId;
    }

    @Override
    @JsonIgnore
    public String messageGroup() {
        return "platform:dispatch-pool:" + applicationId;
    }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(
                poolId, code, name, description, rateLimit, concurrency,
                applicationId, applicationCode, clientId, clientIdentifier, status
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String poolId,
        String code,
        String name,
        String description,
        int rateLimit,
        int concurrency,
        String applicationId,
        String applicationCode,
        String clientId,
        String clientIdentifier,
        DispatchPoolStatus status
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
        private String poolId;
        private String code;
        private String name;
        private String description;
        private int rateLimit;
        private int concurrency;
        private String applicationId;
        private String applicationCode;
        private String clientId;
        private String clientIdentifier;
        private DispatchPoolStatus status;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder poolId(String poolId) {
            this.poolId = poolId;
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

        public Builder rateLimit(int rateLimit) {
            this.rateLimit = rateLimit;
            return this;
        }

        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        public Builder applicationId(String applicationId) {
            this.applicationId = applicationId;
            return this;
        }

        public Builder applicationCode(String applicationCode) {
            this.applicationCode = applicationCode;
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

        public Builder status(DispatchPoolStatus status) {
            this.status = status;
            return this;
        }

        public DispatchPoolUpdated build() {
            return new DispatchPoolUpdated(
                eventId, time, executionId, correlationId, causationId, principalId,
                poolId, code, name, description, rateLimit, concurrency,
                applicationId, applicationCode, clientId, clientIdentifier, status
            );
        }
    }
}
