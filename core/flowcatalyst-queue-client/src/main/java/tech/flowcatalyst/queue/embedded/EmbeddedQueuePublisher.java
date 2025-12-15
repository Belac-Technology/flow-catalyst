package tech.flowcatalyst.queue.embedded;

import org.jboss.logging.Logger;
import tech.flowcatalyst.queue.*;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-based embedded queue publisher.
 * Useful for development and single-node deployments.
 *
 * The queue table stores messages with FIFO ordering per message group.
 */
public class EmbeddedQueuePublisher implements QueuePublisher {

    private static final Logger LOG = Logger.getLogger(EmbeddedQueuePublisher.class);

    private final Connection connection;
    private final String queueName;

    public EmbeddedQueuePublisher(QueueConfig config) throws SQLException {
        String dbPath = config.embeddedDbPath().orElse(":memory:");
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.queueName = config.queueUrl();
        initializeSchema();
    }

    private void initializeSchema() throws SQLException {
        String createTable = """
            CREATE TABLE IF NOT EXISTS queue_messages (
                id TEXT PRIMARY KEY,
                queue_name TEXT NOT NULL,
                message_group_id TEXT,
                deduplication_id TEXT,
                body TEXT NOT NULL,
                status TEXT DEFAULT 'PENDING',
                created_at TEXT NOT NULL,
                processed_at TEXT,
                UNIQUE(queue_name, deduplication_id)
            )
            """;

        String createIndex1 = """
            CREATE INDEX IF NOT EXISTS idx_queue_status
            ON queue_messages(queue_name, status, message_group_id, created_at)
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTable);
            stmt.execute(createIndex1);
        }
    }

    @Override
    public QueuePublishResult publish(QueueMessage message) {
        String sql = """
            INSERT INTO queue_messages (id, queue_name, message_group_id, deduplication_id, body, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(queue_name, deduplication_id) DO NOTHING
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, message.messageId());
            stmt.setString(2, queueName);
            stmt.setString(3, message.messageGroupId());
            stmt.setString(4, message.deduplicationId());
            stmt.setString(5, message.body());
            stmt.setString(6, Instant.now().toString());

            int rows = stmt.executeUpdate();

            if (rows > 0) {
                LOG.debugf("Published message [%s] to embedded queue", message.messageId());
                return QueuePublishResult.success(message.messageId());
            } else {
                LOG.debugf("Message [%s] deduplicated", message.messageId());
                return QueuePublishResult.deduplicated(message.messageId());
            }

        } catch (SQLException e) {
            LOG.errorf(e, "Failed to publish message [%s] to embedded queue", message.messageId());
            return QueuePublishResult.failure(message.messageId(), e.getMessage());
        }
    }

    @Override
    public QueuePublishResult publishBatch(List<QueueMessage> messages) {
        if (messages.isEmpty()) {
            return QueuePublishResult.success(List.of());
        }

        List<String> published = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        StringBuilder errors = new StringBuilder();

        String sql = """
            INSERT INTO queue_messages (id, queue_name, message_group_id, deduplication_id, body, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(queue_name, deduplication_id) DO NOTHING
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (QueueMessage message : messages) {
                try {
                    stmt.setString(1, message.messageId());
                    stmt.setString(2, queueName);
                    stmt.setString(3, message.messageGroupId());
                    stmt.setString(4, message.deduplicationId());
                    stmt.setString(5, message.body());
                    stmt.setString(6, Instant.now().toString());

                    int rows = stmt.executeUpdate();
                    if (rows > 0) {
                        published.add(message.messageId());
                    }
                    // Deduplicated messages are not failures

                } catch (SQLException e) {
                    failed.add(message.messageId());
                    if (!errors.isEmpty()) errors.append("; ");
                    errors.append(message.messageId()).append(": ").append(e.getMessage());
                }
            }
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to prepare batch insert");
            messages.forEach(m -> failed.add(m.messageId()));
            return QueuePublishResult.failure(failed, e.getMessage());
        }

        if (failed.isEmpty()) {
            return QueuePublishResult.success(published);
        } else if (published.isEmpty()) {
            return QueuePublishResult.failure(failed, errors.toString());
        } else {
            return QueuePublishResult.partial(published, failed, errors.toString());
        }
    }

    @Override
    public long getQueueDepth() {
        String sql = "SELECT COUNT(*) FROM queue_messages WHERE queue_name = ? AND status = 'PENDING'";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, queueName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            LOG.warnf("Failed to get queue depth: %s", e.getMessage());
        }
        return -1;
    }

    @Override
    public QueueType getQueueType() {
        return QueueType.EMBEDDED;
    }

    @Override
    public boolean isHealthy() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOG.warnf("Error closing embedded queue connection: %s", e.getMessage());
        }
    }

    /**
     * Poll for next message in a specific message group.
     * Used internally by consumers.
     */
    public QueueMessage pollNextMessage(String messageGroupId) {
        String sql = """
            SELECT id, message_group_id, deduplication_id, body
            FROM queue_messages
            WHERE queue_name = ? AND status = 'PENDING' AND message_group_id = ?
            ORDER BY created_at ASC
            LIMIT 1
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, queueName);
            stmt.setString(2, messageGroupId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new QueueMessage(
                        rs.getString("id"),
                        rs.getString("message_group_id"),
                        rs.getString("deduplication_id"),
                        rs.getString("body")
                    );
                }
            }
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to poll message for group [%s]", messageGroupId);
        }
        return null;
    }

    /**
     * Mark a message as processed.
     */
    public void markProcessed(String messageId) {
        String sql = """
            UPDATE queue_messages
            SET status = 'PROCESSED', processed_at = ?
            WHERE id = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, Instant.now().toString());
            stmt.setString(2, messageId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to mark message [%s] as processed", messageId);
        }
    }

    /**
     * Delete old processed messages (cleanup).
     */
    public int deleteProcessedMessages(int olderThanDays) {
        String sql = """
            DELETE FROM queue_messages
            WHERE status = 'PROCESSED'
            AND processed_at < datetime('now', '-' || ? || ' days')
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, olderThanDays);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf(e, "Failed to delete old messages");
            return 0;
        }
    }
}
