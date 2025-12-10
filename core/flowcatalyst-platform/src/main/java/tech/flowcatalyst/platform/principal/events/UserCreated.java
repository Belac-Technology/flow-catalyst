package tech.flowcatalyst.platform.principal.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.platform.authentication.IdpType;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * Event emitted when a new user is created.
 *
 * <p>Event type: {@code platform:iam:user:created}
 */
public record UserCreated(
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,
    String userId,
    String email,
    String emailDomain,
    String name,
    String clientId,
    IdpType idpType,
    boolean isAnchorUser
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:iam:user:created";
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
        return "platform.user." + userId;
    }

    @Override
    @JsonIgnore
    public String messageGroup() {
        return "platform:user:" + userId;
    }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(userId, email, emailDomain, name, clientId, idpType, isAnchorUser));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String userId,
        String email,
        String emailDomain,
        String name,
        String clientId,
        IdpType idpType,
        boolean isAnchorUser
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
        private String userId;
        private String email;
        private String emailDomain;
        private String name;
        private String clientId;
        private IdpType idpType;
        private boolean isAnchorUser;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder emailDomain(String emailDomain) { this.emailDomain = emailDomain; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder clientId(String clientId) { this.clientId = clientId; return this; }
        public Builder idpType(IdpType idpType) { this.idpType = idpType; return this; }
        public Builder isAnchorUser(boolean isAnchorUser) { this.isAnchorUser = isAnchorUser; return this; }

        public UserCreated build() {
            return new UserCreated(eventId, time, executionId, correlationId, causationId, principalId,
                userId, email, emailDomain, name, clientId, idpType, isAnchorUser);
        }
    }
}
