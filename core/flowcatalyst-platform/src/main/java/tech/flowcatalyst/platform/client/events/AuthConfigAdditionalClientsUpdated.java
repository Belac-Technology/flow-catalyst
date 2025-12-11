package tech.flowcatalyst.platform.client.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.List;

/**
 * Event emitted when a CLIENT auth config's additional clients list is updated.
 *
 * <p>Event type: {@code platform:iam:auth-config:additional-clients-updated}
 */
public record AuthConfigAdditionalClientsUpdated(
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,
    String authConfigId,
    String emailDomain,
    String primaryClientId,
    List<String> previousAdditionalClientIds,
    List<String> newAdditionalClientIds
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:iam:auth-config:additional-clients-updated";
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
                authConfigId, emailDomain, primaryClientId,
                previousAdditionalClientIds, newAdditionalClientIds
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String authConfigId,
        String emailDomain,
        String primaryClientId,
        List<String> previousAdditionalClientIds,
        List<String> newAdditionalClientIds
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
        private String primaryClientId;
        private List<String> previousAdditionalClientIds;
        private List<String> newAdditionalClientIds;

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
        public Builder primaryClientId(String primaryClientId) { this.primaryClientId = primaryClientId; return this; }
        public Builder previousAdditionalClientIds(List<String> previousAdditionalClientIds) { this.previousAdditionalClientIds = previousAdditionalClientIds; return this; }
        public Builder newAdditionalClientIds(List<String> newAdditionalClientIds) { this.newAdditionalClientIds = newAdditionalClientIds; return this; }

        public AuthConfigAdditionalClientsUpdated build() {
            return new AuthConfigAdditionalClientsUpdated(
                eventId, time, executionId, correlationId, causationId, principalId,
                authConfigId, emailDomain, primaryClientId,
                previousAdditionalClientIds, newAdditionalClientIds
            );
        }
    }
}
