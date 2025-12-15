package tech.flowcatalyst.serviceaccount.operations.regenerateauthtoken;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tech.flowcatalyst.platform.common.DomainEvent;
import tech.flowcatalyst.platform.common.ExecutionContext;
import tech.flowcatalyst.platform.shared.TsidGenerator;
import tech.flowcatalyst.serviceaccount.entity.WebhookAuthType;

import java.time.Instant;

/**
 * Event emitted when a service account's auth token is regenerated.
 *
 * <p>Event type: {@code platform:iam:service-account:auth-token-regenerated}
 */
public record AuthTokenRegenerated(
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,
    String serviceAccountId,
    String code,
    WebhookAuthType authType,
    boolean isCustomToken
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:iam:service-account:auth-token-regenerated";
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
            return MAPPER.writeValueAsString(new Data(serviceAccountId, code, authType, isCustomToken));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    public record Data(String serviceAccountId, String code, WebhookAuthType authType, boolean isCustomToken) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String eventId, executionId, correlationId, causationId, principalId;
        private Instant time;
        private String serviceAccountId, code;
        private WebhookAuthType authType;
        private boolean isCustomToken;

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
        public Builder authType(WebhookAuthType v) { this.authType = v; return this; }
        public Builder isCustomToken(boolean v) { this.isCustomToken = v; return this; }

        public AuthTokenRegenerated build() {
            return new AuthTokenRegenerated(eventId, time, executionId, correlationId, causationId, principalId,
                serviceAccountId, code, authType, isCustomToken);
        }
    }
}
