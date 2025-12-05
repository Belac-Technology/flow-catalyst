package tech.flowcatalyst.platform.application;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;

/**
 * Represents an application in the FlowCatalyst platform ecosystem.
 *
 * Applications are the software products that users access. Each application
 * has a unique code that is used as the prefix for roles (e.g., "inmotion:dispatch:admin").
 *
 * Application access is determined by roles:
 * - If a user has any role prefixed with the application code, they can access that app
 * - Anchor domain users get their roles applied to ALL tenants
 * - Partner users get their roles applied to GRANTED tenants only
 * - Tenant users get their roles applied to their OWN tenant only
 */
@Entity
@Table(name = "auth_applications",
    indexes = {
        @Index(name = "idx_auth_application_code", columnList = "code", unique = true),
        @Index(name = "idx_auth_application_active", columnList = "active")
    }
)
public class Application extends PanacheEntityBase {

    @Id
    public Long id;

    /**
     * Unique application code used in role prefixes.
     * Examples: "inmotion", "dispatch", "analytics", "platform"
     */
    @Column(name = "code", unique = true, nullable = false, length = 50)
    public String code;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "description", length = 1000)
    public String description;

    @Column(name = "icon_url", length = 500)
    public String iconUrl;

    /**
     * Default base URL for the application.
     * Can be overridden per tenant via ApplicationTenantConfig.
     */
    @Column(name = "default_base_url", length = 500)
    public String defaultBaseUrl;

    @Column(name = "active", nullable = false)
    public boolean active = true;

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

    public Application() {
    }

    public Application(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
