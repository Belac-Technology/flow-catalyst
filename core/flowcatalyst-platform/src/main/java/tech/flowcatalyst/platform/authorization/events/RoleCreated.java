package tech.flowcatalyst.platform.authorization.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.platform.authorization.AuthRole;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.Set;

/**
 * Event emitted when a new Role is created.
 *
 * <p>Event type: {@code platform:control-plane:role:created}
 */
public record RoleCreated(
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,
    String roleId,
    String roleName,
    String displayName,
    String description,
    String applicationId,
    String applicationCode,
    Set<String> permissions,
    String source,
    boolean clientManaged
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:role:created";
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
        return "platform.role." + roleId;
    }

    @Override
    @JsonIgnore
    public String messageGroup() {
        return "platform:role:" + roleId;
    }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(roleId, roleName, displayName, description,
                applicationId, applicationCode, permissions, source, clientManaged));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String roleId,
        String roleName,
        String displayName,
        String description,
        String applicationId,
        String applicationCode,
        Set<String> permissions,
        String source,
        boolean clientManaged
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
        private String roleId;
        private String roleName;
        private String displayName;
        private String description;
        private String applicationId;
        private String applicationCode;
        private Set<String> permissions;
        private String source;
        private boolean clientManaged;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder roleId(String roleId) { this.roleId = roleId; return this; }
        public Builder roleName(String roleName) { this.roleName = roleName; return this; }
        public Builder displayName(String displayName) { this.displayName = displayName; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder applicationId(String applicationId) { this.applicationId = applicationId; return this; }
        public Builder applicationCode(String applicationCode) { this.applicationCode = applicationCode; return this; }
        public Builder permissions(Set<String> permissions) { this.permissions = permissions; return this; }
        public Builder source(AuthRole.RoleSource source) { this.source = source.name(); return this; }
        public Builder clientManaged(boolean clientManaged) { this.clientManaged = clientManaged; return this; }

        public RoleCreated build() {
            return new RoleCreated(eventId, time, executionId, correlationId, causationId, principalId,
                roleId, roleName, displayName, description, applicationId, applicationCode,
                permissions, source, clientManaged);
        }
    }
}
