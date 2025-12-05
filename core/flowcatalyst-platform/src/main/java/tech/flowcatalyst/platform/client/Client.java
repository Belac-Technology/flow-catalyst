package tech.flowcatalyst.platform.client;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client organization.
 * Only customers get clients (partners don't).
 */
@Entity
@Table(name = "auth_clients",
    indexes = {
        @Index(name = "idx_auth_client_identifier", columnList = "identifier", unique = true),
        @Index(name = "idx_auth_client_status", columnList = "status")
    }
)
public class Client extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "identifier", unique = true, nullable = false, length = 100)
    public String identifier; // Unique client slug/code

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public ClientStatus status = ClientStatus.ACTIVE;

    /**
     * Free-form reason for current status (e.g., "ACCOUNT_NOT_PAID", "TRIAL_EXPIRED").
     * Applications can use their own codes.
     */
    @Column(name = "status_reason", length = 100)
    public String statusReason;

    /**
     * When the status was last changed
     */
    @Column(name = "status_changed_at")
    public Instant statusChangedAt;

    /**
     * Administrative notes and audit trail.
     * Stored as JSONB array.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notes", columnDefinition = "jsonb")
    public List<ClientNote> notes = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = tech.flowcatalyst.platform.shared.TsidGenerator.generate();
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
     * Add a note to the client's audit trail
     */
    public void addNote(String category, String text, String addedBy) {
        notes.add(new ClientNote(category, text, addedBy));
    }

    /**
     * Change client status with reason and optional note
     */
    public void changeStatus(ClientStatus newStatus, String reason, String changeNote, String changedBy) {
        this.status = newStatus;
        this.statusReason = reason;
        this.statusChangedAt = Instant.now();

        if (changeNote != null && !changeNote.isBlank()) {
            addNote("STATUS_CHANGE", changeNote, changedBy);
        }
    }

    public Client() {
    }
}
