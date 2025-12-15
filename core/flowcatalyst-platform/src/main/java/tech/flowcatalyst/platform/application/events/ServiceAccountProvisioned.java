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
 * Event emitted when a service account is provisioned for an Application.
 *
 * <p>Note: The client secret is NOT included in this event for security reasons.
 * It is only returned once at provisioning time via the API response.
 *
 * <p>Event type: {@code platform:control-plane:application:service-account-provisioned}
 */
public record ServiceAccountProvisioned(
    // Event metadata
    String eventId,
    Instant time,
    String executionId,
    String correlationId,
    String causationId,
    String principalId,

    // Event-specific payload (what happened)
    String applicationId,
    String applicationCode,
    String applicationName,
    String serviceAccountId,  // New standalone ServiceAccount entity ID
    String serviceAccountPrincipalId,  // Legacy Principal ID (deprecated)
    String serviceAccountName,
    String oauthClientId,
    String oauthClientClientId  // The client_id used for OAuth (not the entity ID)
) implements DomainEvent {

    private static final String EVENT_TYPE = "platform:control-plane:application:service-account-provisioned";
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
            return MAPPER.writeValueAsString(new Data(
                applicationId, applicationCode, applicationName,
                serviceAccountId, serviceAccountPrincipalId, serviceAccountName,
                oauthClientId, oauthClientClientId
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event data", e);
        }
    }

    /**
     * The event data schema.
     */
    public record Data(
        String applicationId,
        String applicationCode,
        String applicationName,
        String serviceAccountId,
        String serviceAccountPrincipalId,
        String serviceAccountName,
        String oauthClientId,
        String oauthClientClientId
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
        private String applicationId;
        private String applicationCode;
        private String applicationName;
        private String serviceAccountId;
        private String serviceAccountPrincipalId;
        private String serviceAccountName;
        private String oauthClientId;
        private String oauthClientClientId;

        public Builder from(ExecutionContext ctx) {
            this.eventId = TsidGenerator.generate();
            this.time = Instant.now();
            this.executionId = ctx.executionId();
            this.correlationId = ctx.correlationId();
            this.causationId = ctx.causationId();
            this.principalId = ctx.principalId();
            return this;
        }

        public Builder applicationId(String applicationId) {
            this.applicationId = applicationId;
            return this;
        }

        public Builder applicationCode(String applicationCode) {
            this.applicationCode = applicationCode;
            return this;
        }

        public Builder applicationName(String applicationName) {
            this.applicationName = applicationName;
            return this;
        }

        public Builder serviceAccountId(String serviceAccountId) {
            this.serviceAccountId = serviceAccountId;
            return this;
        }

        public Builder serviceAccountPrincipalId(String serviceAccountPrincipalId) {
            this.serviceAccountPrincipalId = serviceAccountPrincipalId;
            return this;
        }

        public Builder serviceAccountName(String serviceAccountName) {
            this.serviceAccountName = serviceAccountName;
            return this;
        }

        public Builder oauthClientId(String oauthClientId) {
            this.oauthClientId = oauthClientId;
            return this;
        }

        public Builder oauthClientClientId(String oauthClientClientId) {
            this.oauthClientClientId = oauthClientClientId;
            return this;
        }

        public ServiceAccountProvisioned build() {
            return new ServiceAccountProvisioned(
                eventId, time, executionId, correlationId, causationId, principalId,
                applicationId, applicationCode, applicationName,
                serviceAccountId, serviceAccountPrincipalId, serviceAccountName,
                oauthClientId, oauthClientClientId
            );
        }
    }
}
