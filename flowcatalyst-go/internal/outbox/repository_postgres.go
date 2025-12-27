package outbox

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
)

// PostgresRepository implements Repository for PostgreSQL
type PostgresRepository struct {
	db     *sql.DB
	config *RepositoryConfig
}

// NewPostgresRepository creates a new PostgreSQL outbox repository
func NewPostgresRepository(db *sql.DB, config *RepositoryConfig) *PostgresRepository {
	if config == nil {
		config = DefaultRepositoryConfig()
	}
	return &PostgresRepository{
		db:     db,
		config: config,
	}
}

// GetTableName returns the table name for the item type
func (r *PostgresRepository) GetTableName(itemType OutboxItemType) string {
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
// Uses PostgreSQL's FOR UPDATE SKIP LOCKED for non-blocking concurrent access.
// CTE + UPDATE...FROM pattern for atomic select-and-update.
func (r *PostgresRepository) FetchAndLockPending(ctx context.Context, itemType OutboxItemType, limit int) ([]*OutboxItem, error) {
	table := r.GetTableName(itemType)

	// PostgreSQL atomic fetch-and-lock using CTE with FOR UPDATE SKIP LOCKED
	query := fmt.Sprintf(`
		WITH selected AS (
			SELECT id FROM %s
			WHERE status = 'PENDING'
			ORDER BY message_group, created_at
			LIMIT $1
			FOR UPDATE SKIP LOCKED
		)
		UPDATE %s t
		SET status = 'PROCESSING', processed_at = NOW()
		FROM selected s
		WHERE t.id = s.id
		RETURNING t.id, t.type, t.message_group, t.payload, t.status, t.retry_count, t.created_at, t.processed_at, t.error_message
	`, table, table)

	rows, err := r.db.QueryContext(ctx, query, limit)
	if err != nil {
		return nil, err
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
			return nil, err
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

	return items, rows.Err()
}

// MarkCompleted updates items to COMPLETED status
func (r *PostgresRepository) MarkCompleted(ctx context.Context, itemType OutboxItemType, ids []string) error {
	if len(ids) == 0 {
		return nil
	}

	table := r.GetTableName(itemType)
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids))
	for i, id := range ids {
		placeholders[i] = fmt.Sprintf("$%d", i+1)
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
func (r *PostgresRepository) MarkFailed(ctx context.Context, itemType OutboxItemType, ids []string, errorMessage string) error {
	if len(ids) == 0 {
		return nil
	}

	table := r.GetTableName(itemType)
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids)+1)
	args[0] = errorMessage
	for i, id := range ids {
		placeholders[i] = fmt.Sprintf("$%d", i+2)
		args[i+1] = id
	}

	query := fmt.Sprintf(`
		UPDATE %s
		SET status = 'FAILED', error_message = $1, processed_at = NOW()
		WHERE id IN (%s)
	`, table, strings.Join(placeholders, ", "))

	_, err := r.db.ExecContext(ctx, query, args...)
	return err
}

// ScheduleRetry increments retryCount and resets to PENDING
func (r *PostgresRepository) ScheduleRetry(ctx context.Context, itemType OutboxItemType, ids []string) error {
	if len(ids) == 0 {
		return nil
	}

	table := r.GetTableName(itemType)
	placeholders := make([]string, len(ids))
	args := make([]interface{}, len(ids))
	for i, id := range ids {
		placeholders[i] = fmt.Sprintf("$%d", i+1)
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
func (r *PostgresRepository) RecoverStuckItems(ctx context.Context, itemType OutboxItemType, timeoutSeconds int) (int64, error) {
	table := r.GetTableName(itemType)

	query := fmt.Sprintf(`
		UPDATE %s
		SET status = 'PENDING'
		WHERE status = 'PROCESSING'
		AND processed_at < NOW() - INTERVAL '%d seconds'
	`, table, timeoutSeconds)

	result, err := r.db.ExecContext(ctx, query)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

// CreateSchema creates the outbox tables if they don't exist
func (r *PostgresRepository) CreateSchema(ctx context.Context) error {
	for _, itemType := range []OutboxItemType{OutboxItemTypeEvent, OutboxItemTypeDispatchJob} {
		table := r.GetTableName(itemType)

		createTable := fmt.Sprintf(`
			CREATE TABLE IF NOT EXISTS %s (
				id VARCHAR(13) PRIMARY KEY,
				type VARCHAR(20) NOT NULL,
				message_group VARCHAR(255),
				payload TEXT NOT NULL,
				status VARCHAR(20) NOT NULL,
				retry_count INT NOT NULL DEFAULT 0,
				created_at TIMESTAMP NOT NULL,
				processed_at TIMESTAMP,
				error_message TEXT
			)
		`, table)

		if _, err := r.db.ExecContext(ctx, createTable); err != nil {
			return err
		}

		// Create index for fetching pending items
		createIndex := fmt.Sprintf(`
			CREATE INDEX IF NOT EXISTS idx_%s_status
			ON %s(status, message_group, created_at)
		`, table, table)

		if _, err := r.db.ExecContext(ctx, createIndex); err != nil {
			return err
		}
	}
	return nil
}
