package tech.flowcatalyst.serviceaccount.operations.assignroles;

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
 * Event emitted when roles are assigned to a service account.
 *
 * <p>Event type: {@code platform:iam:service-account:roles-assigned}
 */
public record RolesAssigned(
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,
    String serviceAccountId,
    String code,
    List<String> roleNames,
    List<String> addedRoles,
    List<String> removedRoles
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:iam:service-account:roles-assigned";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE = "platform:iam";

    @JsonIgnore
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override @JsonIgnore public String eventType() { return EVENT_TYPE; }
    @Override @JsonIgnore public String specVersion() { return SPEC_VERSION; }
    @Override @JsonIgnore public String source() { return SOURCE; }
    @Override @JsonIgnore public String subject() { return "platform.service-account." + serviceAccountId; }
    @Override @JsonIgnore public String messageGroup() { return "platform:service-account:" + serviceAccountId; }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(serviceAccountId, code, roleNames, addedRoles, removedRoles));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(String serviceAccountId, String code, List<String> roleNames,
                       List<String> addedRoles, List<String> removedRoles) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String eventId, executionId, correlationId, causationId, principalId;
        private Instant time;
        private String serviceAccountId, code;
        private List<String> roleNames, addedRoles, removedRoles;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder serviceAccountId(String v) { this.serviceAccountId = v; return this; }
        public Builder code(String v) { this.code = v; return this; }
        public Builder roleNames(List<String> v) { this.roleNames = v; return this; }
        public Builder addedRoles(List<String> v) { this.addedRoles = v; return this; }
        public Builder removedRoles(List<String> v) { this.removedRoles = v; return this; }

        public RolesAssigned build() {
            return new RolesAssigned(eventId, time, executionId, correlationId, causationId, principalId,
                serviceAccountId, code, roleNames, addedRoles, removedRoles);
        }
    }
}
