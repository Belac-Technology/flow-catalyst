package tech.flowcatalyst.platform.authorization.events;

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
 * Event emitted when roles are synced for an application.
 *
 * <p>Event type: {@code platform:control-plane:role:synced}
 */
public record RolesSynced(
    Long eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    Long principalId,
    Long applicationId,
    String applicationCode,
    int rolesCreated,
    int rolesUpdated,
    int rolesDeleted,
    List<String> syncedRoleNames
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:role:synced";
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
        return "platform.application." + applicationId + ".roles";
    }

    @Override
    @JsonIgnore
    public String messageGroup() {
        return "platform:application:" + applicationId;
    }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(applicationId, applicationCode, rolesCreated, rolesUpdated, rolesDeleted, syncedRoleNames));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        Long applicationId,
        String applicationCode,
        int rolesCreated,
        int rolesUpdated,
        int rolesDeleted,
        List<String> syncedRoleNames
    ) {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long eventId;
        private Instant time;
        private String executionId;
        private String correlationId;
        private String causationId;
        private Long principalId;
        private Long applicationId;
        private String applicationCode;
        private int rolesCreated;
        private int rolesUpdated;
        private int rolesDeleted;
        private List<String> syncedRoleNames;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder applicationId(Long applicationId) { this.applicationId = applicationId; return this; }
        public Builder applicationCode(String applicationCode) { this.applicationCode = applicationCode; return this; }
        public Builder rolesCreated(int rolesCreated) { this.rolesCreated = rolesCreated; return this; }
        public Builder rolesUpdated(int rolesUpdated) { this.rolesUpdated = rolesUpdated; return this; }
        public Builder rolesDeleted(int rolesDeleted) { this.rolesDeleted = rolesDeleted; return this; }
        public Builder syncedRoleNames(List<String> syncedRoleNames) { this.syncedRoleNames = syncedRoleNames; return this; }

        public RolesSynced build() {
            return new RolesSynced(eventId, time, executionId, correlationId, causationId, principalId,
                applicationId, applicationCode, rolesCreated, rolesUpdated, rolesDeleted, syncedRoleNames);
        }
    }
}
