// Package outbox implements the Outbox Pattern for reliable message publishing.
// It polls customer databases for pending outbox items and sends them to FlowCatalyst APIs.
package outbox

import (
	"time"
)

// OutboxStatus defines the status of an outbox item
type OutboxStatus string

const (
	OutboxStatusPending    OutboxStatus = "PENDING"
	OutboxStatusProcessing OutboxStatus = "PROCESSING"
	OutboxStatusCompleted  OutboxStatus = "COMPLETED"
	OutboxStatusFailed     OutboxStatus = "FAILED"
)

// OutboxItemType defines the type of outbox item
type OutboxItemType string

const (
	OutboxItemTypeEvent       OutboxItemType = "EVENT"
	OutboxItemTypeDispatchJob OutboxItemType = "DISPATCH_JOB"
)

// OutboxItem represents an item in the outbox table
// This matches Java's tech.flowcatalyst.outboxprocessor.model.OutboxItem
type OutboxItem struct {
	// ID is the unique identifier (TSID format, 13-char Crockford Base32)
	ID string `bson:"_id" json:"id"`

	// Type is the type of outbox item (EVENT or DISPATCH_JOB)
	Type OutboxItemType `bson:"type" json:"type"`

	// MessageGroup is the optional message group for FIFO ordering
	MessageGroup string `bson:"messageGroup,omitempty" json:"messageGroup,omitempty"`

	// Payload is the JSON payload to send to the API
	Payload string `bson:"payload" json:"payload"`

	// Status is the current status of the item
	Status OutboxStatus `bson:"status" json:"status"`

	// RetryCount is the number of retry attempts
	RetryCount int `bson:"retryCount" json:"retryCount"`

	// CreatedAt is when the item was created
	CreatedAt time.Time `bson:"createdAt" json:"createdAt"`

	// ProcessedAt is when the item was locked for processing
	ProcessedAt time.Time `bson:"processedAt,omitempty" json:"processedAt,omitempty"`

	// ErrorMessage is the error message if the item failed
	ErrorMessage string `bson:"errorMessage,omitempty" json:"errorMessage,omitempty"`
}

// IsPending returns true if the item is pending
func (i *OutboxItem) IsPending() bool {
	return i.Status == OutboxStatusPending
}

// IsProcessing returns true if the item is being processed
func (i *OutboxItem) IsProcessing() bool {
	return i.Status == OutboxStatusProcessing
}

// IsCompleted returns true if the item was successfully processed
func (i *OutboxItem) IsCompleted() bool {
	return i.Status == OutboxStatusCompleted
}

// IsFailed returns true if the item failed permanently
func (i *OutboxItem) IsFailed() bool {
	return i.Status == OutboxStatusFailed
}

// GetEffectiveMessageGroup returns the message group or "default" if empty
func (i *OutboxItem) GetEffectiveMessageGroup() string {
	if i.MessageGroup == "" {
		return "default"
	}
	return i.MessageGroup
}

// DatabaseType defines the type of database for outbox storage
type DatabaseType string

const (
	DatabaseTypePostgreSQL DatabaseType = "POSTGRESQL"
	DatabaseTypeMySQL      DatabaseType = "MYSQL"
	DatabaseTypeMongoDB    DatabaseType = "MONGODB"
)
