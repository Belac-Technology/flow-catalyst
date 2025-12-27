package outbox

import (
	"context"
	"time"
)

// Repository defines the interface for outbox data access.
// All implementations must provide atomic fetch-and-lock operations.
type Repository interface {
	// FetchAndLockPending atomically selects pending items AND marks them as PROCESSING.
	// Items are ordered by (messageGroup, createdAt) for FIFO within groups.
	// This MUST be atomic to prevent multiple processors fetching same items.
	FetchAndLockPending(ctx context.Context, itemType OutboxItemType, limit int) ([]*OutboxItem, error)

	// MarkCompleted updates items to COMPLETED status
	MarkCompleted(ctx context.Context, itemType OutboxItemType, ids []string) error

	// MarkFailed marks items as FAILED with error message
	MarkFailed(ctx context.Context, itemType OutboxItemType, ids []string, errorMessage string) error

	// ScheduleRetry increments retryCount and resets to PENDING
	ScheduleRetry(ctx context.Context, itemType OutboxItemType, ids []string) error

	// RecoverStuckItems resets stuck PROCESSING items to PENDING
	// Items in PROCESSING longer than timeoutSeconds are considered stuck
	RecoverStuckItems(ctx context.Context, itemType OutboxItemType, timeoutSeconds int) (int64, error)

	// GetTableName returns the table/collection name for the item type
	GetTableName(itemType OutboxItemType) string
}

// RepositoryConfig holds configuration for the outbox repository
type RepositoryConfig struct {
	// EventsTable is the table name for event outbox items
	EventsTable string

	// DispatchJobsTable is the table name for dispatch job outbox items
	DispatchJobsTable string

	// DatabaseType is the type of database
	DatabaseType DatabaseType
}

// DefaultRepositoryConfig returns sensible defaults
func DefaultRepositoryConfig() *RepositoryConfig {
	return &RepositoryConfig{
		EventsTable:       "outbox_events",
		DispatchJobsTable: "outbox_dispatch_jobs",
		DatabaseType:      DatabaseTypeMongoDB,
	}
}

// BatchResult represents the result of a batch API call
type BatchResult struct {
	SuccessIDs []string
	FailedIDs  []string
	Error      error
}

// ProcessingResult represents the result of processing outbox items
type ProcessingResult struct {
	Completed  int
	Failed     int
	Retried    int
	TotalItems int
	Duration   time.Duration
}
