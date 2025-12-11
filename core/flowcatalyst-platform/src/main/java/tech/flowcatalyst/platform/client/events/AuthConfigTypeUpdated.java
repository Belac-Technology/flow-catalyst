package tech.flowcatalyst.platform.client.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.platform.client.AuthConfigType;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * Event emitted when an auth config's type is changed.
 *
 * <p>Event type: {@code platform:iam:auth-config:type-updated}
 */
public record AuthConfigTypeUpdated(
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,
    String authConfigId,
    String emailDomain,
    AuthConfigType previousType,
    AuthConfigType newType,
    String previousPrimaryClientId,
    String newPrimaryClientId
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:iam:auth-config:type-updated";
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
        return "platform.auth-config." + authConfigId;
    }

    @Override
    @JsonIgnore
    public String messageGroup() {
        return "platform:auth-config:" + authConfigId;
    }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(
                authConfigId, emailDomain, previousType, newType,
                previousPrimaryClientId, newPrimaryClientId
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String authConfigId,
        String emailDomain,
        AuthConfigType previousType,
        AuthConfigType newType,
        String previousPrimaryClientId,
        String newPrimaryClientId
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
        private String authConfigId;
        private String emailDomain;
        private AuthConfigType previousType;
        private AuthConfigType newType;
        private String previousPrimaryClientId;
        private String newPrimaryClientId;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder authConfigId(String authConfigId) { this.authConfigId = authConfigId; return this; }
        public Builder emailDomain(String emailDomain) { this.emailDomain = emailDomain; return this; }
        public Builder previousType(AuthConfigType previousType) { this.previousType = previousType; return this; }
        public Builder newType(AuthConfigType newType) { this.newType = newType; return this; }
        public Builder previousPrimaryClientId(String previousPrimaryClientId) { this.previousPrimaryClientId = previousPrimaryClientId; return this; }
        public Builder newPrimaryClientId(String newPrimaryClientId) { this.newPrimaryClientId = newPrimaryClientId; return this; }

        public AuthConfigTypeUpdated build() {
            return new AuthConfigTypeUpdated(
                eventId, time, executionId, correlationId, causationId, principalId,
                authConfigId, emailDomain, previousType, newType,
                previousPrimaryClientId, newPrimaryClientId
            );
        }
    }
}
