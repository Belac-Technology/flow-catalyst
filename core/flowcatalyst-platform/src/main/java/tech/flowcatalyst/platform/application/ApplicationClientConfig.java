package tech.flowcatalyst.platform.application;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tech.flowcatalyst.platform.shared.TsidGenerator;
import tech.flowcatalyst.platform.client.Client;

import java.time.Instant;
import java.util.Map;

/**
 * Per-client configuration for an application.
 *
 * Allows clients to have:
 * - Custom base URL (e.g., client1.inmotion.com instead of inmotion.com)
 * - Enabled/disabled status per application
 * - Custom configuration settings
 */
@Entity
@Table(name = "auth_application_client_config",
    indexes = {
        @Index(name = "idx_auth_app_client_config_app", columnList = "application_id"),
        @Index(name = "idx_auth_app_client_config_client", columnList = "client_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "unique_app_client", columnNames = {"application_id", "client_id"})
    }
)
public class ApplicationClientConfig extends PanacheEntityBase {

    @Id
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    public Application application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    public Client client;

    /**
     * Whether this client has access to this application.
     * Even if a user has roles for an app, the app must be enabled for their client.
     */
    @Column(name = "enabled", nullable = false)
    public boolean enabled = true;

    /**
     * Client-specific URL override.
     * If set, this URL is used instead of the application's defaultBaseUrl.
     * Example: "client1.inmotion.com" instead of "inmotion.com"
     */
    @Column(name = "base_url_override", length = 500)
    public String baseUrlOverride;

    /**
     * Additional client-specific configuration as JSON.
     * Can include branding, feature flags, etc.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb")
    public Map<String, Object> configJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = TsidGenerator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Get the effective base URL for this client's application.
     * Returns the override if set, otherwise the application's default.
     */
    public String getEffectiveBaseUrl() {
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) {
            return baseUrlOverride;
        }
        return application != null ? application.defaultBaseUrl : null;
    }

    public ApplicationClientConfig() {
    }
}
