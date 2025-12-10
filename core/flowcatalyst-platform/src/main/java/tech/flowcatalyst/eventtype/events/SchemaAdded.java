package tech.flowcatalyst.eventtype.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.eventtype.SchemaType;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * Event emitted when a new schema version is added to an EventType.
 *
 * <p>Event type: {@code platform:control-plane:eventtype:schema-added}
 *
 * <p>The new schema is added in FINALISING status and must be finalised
 * before it can be used for event validation.
 */
public record SchemaAdded(
    // Event metadata
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,

    // Event-specific payload
    String eventTypeId,
    String version,
    String mimeType,
    String schema,
    SchemaType schemaType
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:eventtype:schema-added";
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
            return MAPPER.writeValueAsString(new Data(eventTypeId, version, mimeType, schema, schemaType));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(
        String eventTypeId,
        String version,
        String mimeType,
        String schema,
        SchemaType schemaType
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
        private String eventTypeId;
        private String version;
        private String mimeType;
        private String schema;
        private SchemaType schemaType;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder eventTypeId(String eventTypeId) {
            this.eventTypeId = eventTypeId;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        public Builder schemaType(SchemaType schemaType) {
            this.schemaType = schemaType;
            return this;
        }

        public SchemaAdded build() {
            return new SchemaAdded(
                eventId, time, executionId, correlationId, causationId, principalId,
                eventTypeId, version, mimeType, schema, schemaType
            );
        }
    }
}
