package tech.flowcatalyst.outbox.repository.postgres;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.outbox.config.OutboxProcessorConfig;
import tech.flowcatalyst.outbox.model.OutboxItem;
import tech.flowcatalyst.outbox.model.OutboxItemType;
import tech.flowcatalyst.outbox.model.OutboxStatus;
import tech.flowcatalyst.outbox.repository.OutboxRepository;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of OutboxRepository.
 * Uses SELECT FOR UPDATE SKIP LOCKED for concurrent access.
 */
@ApplicationScoped
public class PostgresOutboxRepository implements OutboxRepository {

    private static final Logger LOG = Logger.getLogger(PostgresOutboxRepository.class);

    @Inject
    AgroalDataSource dataSource;

    @Inject
    OutboxProcessorConfig config;

    @Override
    public List<OutboxItem> fetchAndLockPending(OutboxItemType type, int limit) {
        String table = getTableName(type);

        // Use UPDATE ... FROM subquery with FOR UPDATE SKIP LOCKED
        // This atomically selects and updates, preventing race conditions
        String sql = """
            WITH selected AS (
                SELECT id FROM %s
                WHERE status = 'PENDING'
                ORDER BY message_group, created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE %s t
            SET status = 'PROCESSING', processed_at = NOW()
            FROM selected s
            WHERE t.id = s.id
            RETURNING t.id, t.message_group, t.payload, t.status, t.retry_count,
                      t.created_at, t.processed_at, t.error_message
            """.formatted(table, table);

        List<OutboxItem> items = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs, type));
                }
            }

        } catch (SQLException e) {
            LOG.errorf(e, "Failed to fetch and lock pending items from %s", table);
            throw new RuntimeException("Failed to fetch pending items", e);
        }

        return items;
    }

    @Override
    public void markCompleted(OutboxItemType type, List<String> ids) {
        if (ids.isEmpty()) return;

        String table = getTableName(type);
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());

        String sql = "UPDATE %s SET status = 'COMPLETED', processed_at = NOW() WHERE id IN (%s)"
            .formatted(table, placeholders);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(i + 1, ids.get(i));
            }

            int updated = stmt.executeUpdate();
            LOG.debugf("Marked %d items as COMPLETED in %s", updated, table);

        } catch (SQLException e) {
            LOG.errorf(e, "Failed to mark items as completed in %s", table);
            throw new RuntimeException("Failed to mark items as completed", e);
        }
    }

    @Override
    public void markFailed(OutboxItemType type, List<String> ids, String errorMessage) {
        if (ids.isEmpty()) return;

        String table = getTableName(type);
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());

        String sql = "UPDATE %s SET status = 'FAILED', error_message = ?, processed_at = NOW() WHERE id IN (%s)"
            .formatted(table, placeholders);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, errorMessage);
            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(i + 2, ids.get(i));
            }

            int updated = stmt.executeUpdate();
            LOG.debugf("Marked %d items as FAILED in %s", updated, table);

        } catch (SQLException e) {
            LOG.errorf(e, "Failed to mark items as failed in %s", table);
            throw new RuntimeException("Failed to mark items as failed", e);
        }
    }

    @Override
    public void scheduleRetry(OutboxItemType type, List<String> ids) {
        if (ids.isEmpty()) return;

        String table = getTableName(type);
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());

        // Increment retry_count and set status back to PENDING
        String sql = """
            UPDATE %s
            SET status = 'PENDING',
                retry_count = retry_count + 1,
                processed_at = NULL
            WHERE id IN (%s) AND retry_count < ?
            """.formatted(table, placeholders);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < ids.size(); i++) {
                stmt.setString(i + 1, ids.get(i));
            }
            stmt.setInt(ids.size() + 1, config.maxRetries());

            int updated = stmt.executeUpdate();
            LOG.debugf("Scheduled %d items for retry in %s", updated, table);

        } catch (SQLException e) {
            LOG.errorf(e, "Failed to schedule retry for items in %s", table);
            throw new RuntimeException("Failed to schedule retry", e);
        }
    }

    @Override
    public int recoverStuckItems(OutboxItemType type, int timeoutSeconds) {
        String table = getTableName(type);

        // Reset items stuck in PROCESSING for longer than timeout
        String sql = """
            UPDATE %s
            SET status = 'PENDING', processed_at = NULL
            WHERE status = 'PROCESSING'
              AND processed_at < NOW() - INTERVAL '%d seconds'
            """.formatted(table, timeoutSeconds);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int recovered = stmt.executeUpdate();
            if (recovered > 0) {
                LOG.infof("Recovered %d stuck items in %s", recovered, table);
            }
            return recovered;

        } catch (SQLException e) {
            LOG.errorf(e, "Failed to recover stuck items in %s", table);
            throw new RuntimeException("Failed to recover stuck items", e);
        }
    }

    private String getTableName(OutboxItemType type) {
        return type == OutboxItemType.EVENT ? config.eventsTable() : config.dispatchJobsTable();
    }

    private OutboxItem mapRow(ResultSet rs, OutboxItemType type) throws SQLException {
        return new OutboxItem(
            rs.getString("id"),
            type,
            rs.getString("message_group"),
            rs.getString("payload"),
            OutboxStatus.valueOf(rs.getString("status")),
            rs.getInt("retry_count"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("processed_at")),
            rs.getString("error_message")
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}
