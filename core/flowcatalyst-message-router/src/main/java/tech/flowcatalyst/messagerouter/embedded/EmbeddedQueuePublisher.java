package tech.flowcatalyst.messagerouter.embedded;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Publisher for embedded SQLite queue with SQS FIFO semantics.
 * Handles message deduplication and FIFO ordering per message group.
 */
@ApplicationScoped
public class EmbeddedQueuePublisher {

    private static final Logger LOG = Logger.getLogger(EmbeddedQueuePublisher.class);
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000; // 5 minutes

    @Inject
    @io.quarkus.agroal.DataSource("embedded-queue")
    AgroalDataSource dataSource;

    /**
     * Publish a message to the embedded queue.
     *
     * @param messageId Message ID (must be unique)
     * @param messageGroupId Message group ID for FIFO ordering
     * @param messageDeduplicationId Optional deduplication ID
     * @param messageJson JSON payload
     * @return true if published, false if deduplicated
     */
    public boolean publishMessage(
            String messageId,
            String messageGroupId,
            String messageDeduplicationId,
            String messageJson) {

        try {
            // Check deduplication if dedup ID provided
            if (messageDeduplicationId != null && isDuplicate(messageDeduplicationId)) {
                LOG.debugf("Message [%s] deduplicated (dedup ID: %s)", messageId, messageDeduplicationId);
                return false;
            }

            long now = System.currentTimeMillis();
            String receiptHandle = UUID.randomUUID().toString();

            // Insert message
            String insertSql = """
                INSERT INTO queue_messages
                (message_id, message_group_id, message_deduplication_id, message_json,
                 created_at, visible_at, receipt_handle, receive_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(insertSql)) {

                stmt.setString(1, messageId);
                stmt.setString(2, messageGroupId);
                stmt.setString(3, messageDeduplicationId);
                stmt.setString(4, messageJson);
                stmt.setLong(5, now);
                stmt.setLong(6, now); // Immediately visible
                stmt.setString(7, receiptHandle);

                stmt.executeUpdate();
            }

            // Record deduplication entry if dedup ID provided
            if (messageDeduplicationId != null) {
                recordDeduplication(messageDeduplicationId, messageId, now);
            }

            // Clean up old deduplication entries (older than 5 minutes)
            cleanupOldDeduplicationEntries(now - DEDUP_WINDOW_MS);

            LOG.debugf("Published message [%s] to group [%s]", messageId, messageGroupId);
            return true;

        } catch (Exception e) {
            LOG.errorf(e, "Error publishing message [%s]", messageId);
            throw new RuntimeException("Failed to publish message", e);
        }
    }

    /**
     * Check if message with dedup ID was already seen in the last 5 minutes.
     */
    private boolean isDuplicate(String dedupId) throws Exception {
        String sql = "SELECT message_id FROM message_deduplication WHERE message_deduplication_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, dedupId);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Record deduplication entry for 5-minute window.
     */
    private void recordDeduplication(String dedupId, String messageId, long timestamp) throws Exception {
        String sql = """
            INSERT OR REPLACE INTO message_deduplication
            (message_deduplication_id, message_id, created_at)
            VALUES (?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, dedupId);
            stmt.setString(2, messageId);
            stmt.setLong(3, timestamp);

            stmt.executeUpdate();
        }
    }

    /**
     * Clean up deduplication entries older than the dedup window.
     */
    private void cleanupOldDeduplicationEntries(long olderThan) {
        try {
            String sql = "DELETE FROM message_deduplication WHERE created_at < ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setLong(1, olderThan);
                int deleted = stmt.executeUpdate();

                if (deleted > 0) {
                    LOG.debugf("Cleaned up %d old deduplication entries", deleted);
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "Error cleaning up deduplication entries");
        }
    }

    /**
     * Get queue depth (approximate message count).
     */
    public long getQueueDepth() {
        try {
            String sql = "SELECT COUNT(*) FROM queue_messages WHERE visible_at <= ?";
            long now = System.currentTimeMillis();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setLong(1, now);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }
        } catch (Exception e) {
            LOG.errorf(e, "Error getting queue depth");
        }
        return 0;
    }
}
