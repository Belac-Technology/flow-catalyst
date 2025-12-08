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
import java.util.Set;

/**
 * Event emitted when a Role is updated.
 *
 * <p>Event type: {@code platform:control-plane:role:updated}
 */
public record RoleUpdated(
    Long eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    Long principalId,
    Long roleId,
    String roleName,
    String displayName,
    String description,
    Set<String> permissions,
    boolean clientManaged
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:role:updated";
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
            return MAPPER.writeValueAsString(new Data(roleId, roleName, displayName, description, permissions, clientManaged));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        Long roleId,
        String roleName,
        String displayName,
        String description,
        Set<String> permissions,
        boolean clientManaged
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
        private Long roleId;
        private String roleName;
        private String displayName;
        private String description;
        private Set<String> permissions;
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

        public Builder roleId(Long roleId) { this.roleId = roleId; return this; }
        public Builder roleName(String roleName) { this.roleName = roleName; return this; }
        public Builder displayName(String displayName) { this.displayName = displayName; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder permissions(Set<String> permissions) { this.permissions = permissions; return this; }
        public Builder clientManaged(boolean clientManaged) { this.clientManaged = clientManaged; return this; }

        public RoleUpdated build() {
            return new RoleUpdated(eventId, time, executionId, correlationId, causationId, principalId,
                roleId, roleName, displayName, description, permissions, clientManaged);
        }
    }
}
