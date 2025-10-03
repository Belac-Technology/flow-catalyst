package tech.flowcatalyst.messagerouter.embedded;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.agroal.api.AgroalDataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.flowcatalyst.messagerouter.config.QueueType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Initializes the SQLite database schema for the embedded queue.
 * Creates tables on application startup if they don't exist.
 * Only initializes when queue type is EMBEDDED.
 */
@ApplicationScoped
public class EmbeddedQueueSchema {

    private static final Logger LOG = Logger.getLogger(EmbeddedQueueSchema.class);

    @Inject
    @ConfigProperty(name = "message-router.queue-type", defaultValue = "SQS")
    QueueType queueType;

    @Inject
    @io.quarkus.agroal.DataSource("embedded-queue")
    AgroalDataSource dataSource;

    void onStart(@Observes StartupEvent ev) {
        // Only initialize if using embedded queue
        if (queueType != QueueType.EMBEDDED) {
            LOG.infof("Queue type is %s, skipping embedded queue schema initialization", queueType);
            return;
        }

        LOG.info("Initializing embedded queue schema...");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Main queue table with SQS FIFO semantics
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS queue_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id TEXT UNIQUE NOT NULL,
                    message_group_id TEXT NOT NULL,
                    message_deduplication_id TEXT,
                    message_json TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    visible_at INTEGER NOT NULL,
                    receipt_handle TEXT UNIQUE NOT NULL,
                    receive_count INTEGER DEFAULT 0,
                    first_received_at INTEGER
                )
                """);

            // Index for efficient message group ordering with visibility
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_group_visibility
                ON queue_messages(message_group_id, visible_at, id)
                """);

            // Index for finding next available message across groups
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_visibility_id
                ON queue_messages(visible_at, id)
                """);

            // Deduplication tracking (messages seen in last 5 minutes)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS message_deduplication (
                    message_deduplication_id TEXT PRIMARY KEY,
                    message_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_dedup_created
                ON message_deduplication(created_at)
                """);

            LOG.info("Embedded queue schema initialized successfully");

        } catch (Exception e) {
            LOG.error("Failed to initialize embedded queue schema", e);
            throw new RuntimeException("Failed to initialize embedded queue schema", e);
        }
    }
}
