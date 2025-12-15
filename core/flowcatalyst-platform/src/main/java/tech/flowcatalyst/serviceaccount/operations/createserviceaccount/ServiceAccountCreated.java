package tech.flowcatalyst.serviceaccount.operations.createserviceaccount;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * Event emitted when a new service account is created.
 *
 * <p>Event type: {@code platform:iam:service-account:created}
 */
public record ServiceAccountCreated(
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,
    String serviceAccountId,
    String code,
    String name,
    String clientId,
    String applicationId
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:iam:service-account:created";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE = "platform:iam";

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
        return "platform.service-account." + serviceAccountId;
    }

    @Override
    @JsonIgnore
    public String messageGroup() {
        return "platform:service-account:" + serviceAccountId;
    }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(serviceAccountId, code, name, clientId, applicationId));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String serviceAccountId,
        String code,
        String name,
        String clientId,
        String applicationId
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
        private String serviceAccountId;
        private String code;
        private String name;
        private String clientId;
        private String applicationId;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder serviceAccountId(String serviceAccountId) { this.serviceAccountId = serviceAccountId; return this; }
        public Builder code(String code) { this.code = code; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder clientId(String clientId) { this.clientId = clientId; return this; }
        public Builder applicationId(String applicationId) { this.applicationId = applicationId; return this; }

        public ServiceAccountCreated build() {
            return new ServiceAccountCreated(eventId, time, executionId, correlationId, causationId, principalId,
                serviceAccountId, code, name, clientId, applicationId);
        }
    }
}
