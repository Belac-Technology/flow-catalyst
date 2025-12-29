package outbox

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
)

// MySQLRepository implements Repository for MySQL
type MySQLRepository struct {
	db     *sql.DB
	config *RepositoryConfig
}

// NewMySQLRepository creates a new MySQL outbox repository
func NewMySQLRepository(db *sql.DB, config *RepositoryConfig) *MySQLRepository {
	if config == nil {
		config = DefaultRepositoryConfig()
	}
	return &MySQLRepository{
		db:     db,
		config: config,
	}
}

// GetTableName returns the table name for the item type
func (r *MySQLRepository) GetTableName(itemType OutboxItemType) string {
	switch itemType {
	case OutboxItemTypeEvent:
		return r.config.EventsTable
	case OutboxItemTypeDispatchJob:
		return r.config.DispatchJobsTable
	default:
		return r.config.EventsTable
	}
}

// FetchAndLockPending atomically fetches pending items and marks them as PROCESSING.
// Uses MySQL's FOR UPDATE SKIP LOCKED for non-blocking concurrent access.
// Since MySQL doesn't support CTE with RETURNING, we use a 3-step transaction:
// 1. SELECT ids FOR UPDATE SKIP LOCKED
// 2. UPDATE status to PROCESSING
// 3. SELECT full rows by ids
func (r *MySQLRepository) FetchAndLockPending(ctx context.Context, itemType OutboxItemType, limit int) ([]*OutboxItem, error) {
	table := r.GetTableName(itemType)

	// Begin transaction
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() {
		if err != nil {
			tx.Rollback()
		}
	}()

	// Step 1: SELECT ids with FOR UPDATE SKIP LOCKED
	selectQuery := fmt.Sprintf(`
		SELECT id FROM %s
		WHERE status = 'PENDING'
		ORDER BY message_group, created_at
		LIMIT ?
		FOR UPDATE SKIP LOCKED
	`, table)

	rows, err := tx.QueryContext(ctx, selectQuery, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to select pending items: %w", err)
	}

	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, fmt.Errorf("failed to scan id: %w", err)
		}
		ids = append(ids, id)
	}
	rows.Close()

	if len(ids) == 0 {
		tx.Commit()
		return nil, nil
	}

	// Step 2: UPDATE status to PROCESSING
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids))
	for i, id := range ids {
		placeholders[i] = "?"
		args[i] = id
	}

	updateQuery := fmt.Sprintf(`
		UPDATE %s
		SET status = 'PROCESSING', processed_at = NOW()
		WHERE id IN (%s)
	`, table, strings.Join(placeholders, ", "))

	_, err = tx.ExecContext(ctx, updateQuery, args...)
	if err != nil {
		return nil, fmt.Errorf("failed to update status: %w", err)
	}

	// Step 3: SELECT full rows
	selectFullQuery := fmt.Sprintf(`
		SELECT id, type, message_group, payload, status, retry_count, created_at, processed_at, error_message
		FROM %s
		WHERE id IN (%s)
		ORDER BY message_group, created_at
	`, table, strings.Join(placeholders, ", "))

	rows, err = tx.QueryContext(ctx, selectFullQuery, args...)
	if err != nil {
		return nil, fmt.Errorf("failed to select full items: %w", err)
	}
	defer rows.Close()

	var items []*OutboxItem
	for rows.Next() {
		var item OutboxItem
		var messageGroup, errorMessage sql.NullString
		var processedAt sql.NullTime

		err := rows.Scan(
			&item.ID,
			&item.Type,
			&messageGroup,
			&item.Payload,
			&item.Status,
			&item.RetryCount,
			&item.CreatedAt,
			&processedAt,
			&errorMessage,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan item: %w", err)
		}

		if messageGroup.Valid {
			item.MessageGroup = messageGroup.String
		}
		if processedAt.Valid {
			item.ProcessedAt = processedAt.Time
		}
		if errorMessage.Valid {
			item.ErrorMessage = errorMessage.String
		}

		items = append(items, &item)
	}

	if err = rows.Err(); err != nil {
		return nil, fmt.Errorf("error iterating rows: %w", err)
	}

	// Commit transaction
	if err = tx.Commit(); err != nil {
		return nil, fmt.Errorf("failed to commit transaction: %w", err)
	}

	return items, nil
}

// MarkCompleted updates items to COMPLETED status
func (r *MySQLRepository) MarkCompleted(ctx context.Context, itemType OutboxItemType, ids []string) error {
	if len(ids) == 0 {
		return nil
	}

	table := r.GetTableName(itemType)
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids))
	for i, id := range ids {
		placeholders[i] = "?"
		args[i] = id
	}

	query := fmt.Sprintf(`
		UPDATE %s
		SET status = 'COMPLETED', processed_at = NOW()
		WHERE id IN (%s)
	`, table, strings.Join(placeholders, ", "))

	_, err := r.db.ExecContext(ctx, query, args...)
	return err
}

// MarkFailed marks items as FAILED with error message
func (r *MySQLRepository) MarkFailed(ctx context.Context, itemType OutboxItemType, ids []string, errorMessage string) error {
	if len(ids) == 0 {
		return nil
	}

	table := r.GetTableName(itemType)
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids)+1)
	args[0] = errorMessage
	for i, id := range ids {
		placeholders[i] = "?"
		args[i+1] = id
	}

	query := fmt.Sprintf(`
		UPDATE %s
		SET status = 'FAILED', error_message = ?, processed_at = NOW()
		WHERE id IN (%s)
	`, table, strings.Join(placeholders, ", "))

	_, err := r.db.ExecContext(ctx, query, args...)
	return err
}

// ScheduleRetry increments retryCount and resets to PENDING
func (r *MySQLRepository) ScheduleRetry(ctx context.Context, itemType OutboxItemType, ids []string) error {
	if len(ids) == 0 {
		return nil
	}

	table := r.GetTableName(itemType)
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids))
	for i, id := range ids {
		placeholders[i] = "?"
		args[i] = id
	}

	query := fmt.Sprintf(`
		UPDATE %s
		SET status = 'PENDING', retry_count = retry_count + 1
		WHERE id IN (%s)
	`, table, strings.Join(placeholders, ", "))

	_, err := r.db.ExecContext(ctx, query, args...)
	return err
}

// RecoverStuckItems resets stuck PROCESSING items to PENDING
func (r *MySQLRepository) RecoverStuckItems(ctx context.Context, itemType OutboxItemType, timeoutSeconds int) (int64, error) {
	table := r.GetTableName(itemType)

	// MySQL uses INTERVAL n SECOND syntax
	query := fmt.Sprintf(`
		UPDATE %s
		SET status = 'PENDING'
		WHERE status = 'PROCESSING'
		AND processed_at < NOW() - INTERVAL ? SECOND
	`, table)

	result, err := r.db.ExecContext(ctx, query, timeoutSeconds)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

// CreateSchema creates the outbox tables if they don't exist
func (r *MySQLRepository) CreateSchema(ctx context.Context) error {
	for _, itemType := range []OutboxItemType{OutboxItemTypeEvent, OutboxItemTypeDispatchJob} {
		table := r.GetTableName(itemType)

		// MySQL-specific DDL with appropriate data types
		createTable := fmt.Sprintf(`
			CREATE TABLE IF NOT EXISTS %s (
				id VARCHAR(13) PRIMARY KEY,
				type VARCHAR(20) NOT NULL,
				message_group VARCHAR(255),
				payload TEXT NOT NULL,
				status VARCHAR(20) NOT NULL,
				retry_count INT NOT NULL DEFAULT 0,
				created_at DATETIME(3) NOT NULL,
				processed_at DATETIME(3),
				error_message TEXT,
				INDEX idx_%s_status (status, message_group, created_at)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
		`, table, table)

		if _, err := r.db.ExecContext(ctx, createTable); err != nil {
			return fmt.Errorf("failed to create table %s: %w", table, err)
		}
	}
	return nil
}
