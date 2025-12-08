package tech.flowcatalyst.eventtype.events;

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
 * Event emitted when a schema version is finalised.
 *
 * <p>Event type: {@code platform:control-plane:eventtype:schema-finalised}
 *
 * <p>Finalising a schema moves it from FINALISING to CURRENT status.
 * Any existing CURRENT schema with the same major version is automatically
 * deprecated.
 */
public record SchemaFinalised(
    // Event metadata
    Long eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    Long principalId,

    // Event-specific payload
    Long eventTypeId,
    String version,
    String deprecatedVersion  // The version that was deprecated (if any)
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:eventtype:schema-finalised";
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
        return "platform.eventtype." + eventTypeId;
    }

    @Override
    @JsonIgnore
    public String messageGroup() {
        return "platform:eventtype:" + eventTypeId;
    }

    @Override
    @JsonIgnore
    public String toDataJson() {
        try {
            return MAPPER.writeValueAsString(new Data(eventTypeId, version, deprecatedVersion));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        Long eventTypeId,
        String version,
        String deprecatedVersion
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
        private Long eventTypeId;
        private String version;
        private String deprecatedVersion;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder eventTypeId(Long eventTypeId) {
            this.eventTypeId = eventTypeId;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder deprecatedVersion(String deprecatedVersion) {
            this.deprecatedVersion = deprecatedVersion;
            return this;
        }

        public SchemaFinalised build() {
            return new SchemaFinalised(
                eventId, time, executionId, correlationId, causationId, principalId,
                eventTypeId, version, deprecatedVersion
            );
        }
    }
}
