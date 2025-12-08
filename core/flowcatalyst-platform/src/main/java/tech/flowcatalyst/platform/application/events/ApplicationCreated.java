package tech.flowcatalyst.platform.application.events;

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
 * Event emitted when a new Application is created.
 *
 * <p>Event type: {@code platform:control-plane:application:created}
 */
public record ApplicationCreated(
    // Event metadata
    Long eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    Long principalId,

    // Event-specific payload (what happened)
    Long applicationId,
    String code,
    String name,
    String description,
    String defaultBaseUrl,
    String iconUrl
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:application:created";
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
        return "platform.application." + applicationId;
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
            return MAPPER.writeValueAsString(new Data(applicationId, code, name, description, defaultBaseUrl, iconUrl));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    /**
     * The event data schema.
     */
    public record Data(
        Long applicationId,
        String code,
        String name,
        String description,
        String defaultBaseUrl,
        String iconUrl
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
        private String code;
        private String name;
        private String description;
        private String defaultBaseUrl;
        private String iconUrl;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder applicationId(Long applicationId) {
            this.applicationId = applicationId;
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

        public Builder defaultBaseUrl(String defaultBaseUrl) {
            this.defaultBaseUrl = defaultBaseUrl;
            return this;
        }

        public Builder iconUrl(String iconUrl) {
            this.iconUrl = iconUrl;
            return this;
        }

        public ApplicationCreated build() {
            return new ApplicationCreated(
                eventId, time, executionId, correlationId, causationId, principalId,
                applicationId, code, name, description, defaultBaseUrl, iconUrl
            );
        }
    }
}
