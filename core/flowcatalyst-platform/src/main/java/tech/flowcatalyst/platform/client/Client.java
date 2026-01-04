package tech.flowcatalyst.platform.client;

import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client organization.
 * Only customers get clients (partners don't).
 */
public class Client {

    @BsonId
    public String id;

    public String name;

    public String identifier; // Unique client slug/code

    public ClientStatus status = ClientStatus.ACTIVE;

    /**
     * Free-form reason for current status (e.g., "ACCOUNT_NOT_PAID", "TRIAL_EXPIRED").
     * Applications can use their own codes.
     */
    public String statusReason;

    /**
     * When the status was last changed
     */
    public Instant statusChangedAt;

    /**
     * Administrative notes and audit trail.
     */
    public List<ClientNote> notes = new ArrayList<>();

    public Instant createdAt = Instant.now();

    public Instant updatedAt = Instant.now();

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
