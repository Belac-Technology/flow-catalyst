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
 * Event emitted when a PARTNER auth config's granted clients list is updated.
 *
 * <p>Event type: {@code platform:iam:auth-config:granted-clients-updated}
 */
public record AuthConfigGrantedClientsUpdated(
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,
    String authConfigId,
    String emailDomain,
    List<String> previousGrantedClientIds,
    List<String> newGrantedClientIds
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:iam:auth-config:granted-clients-updated";
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
                authConfigId, emailDomain, previousGrantedClientIds, newGrantedClientIds
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String authConfigId,
        String emailDomain,
        List<String> previousGrantedClientIds,
        List<String> newGrantedClientIds
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
        private List<String> previousGrantedClientIds;
        private List<String> newGrantedClientIds;

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
        public Builder previousGrantedClientIds(List<String> previousGrantedClientIds) { this.previousGrantedClientIds = previousGrantedClientIds; return this; }
        public Builder newGrantedClientIds(List<String> newGrantedClientIds) { this.newGrantedClientIds = newGrantedClientIds; return this; }

        public AuthConfigGrantedClientsUpdated build() {
            return new AuthConfigGrantedClientsUpdated(
                eventId, time, executionId, correlationId, causationId, principalId,
                authConfigId, emailDomain, previousGrantedClientIds, newGrantedClientIds
            );
        }
    }
}
