// Package stream provides MongoDB change stream processing
package stream

import (
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
)

// EventProjectionMapper maps event documents to event read projections
type EventProjectionMapper struct{}

// NewEventProjectionMapper creates a new event projection mapper
func NewEventProjectionMapper() *EventProjectionMapper {
	return &EventProjectionMapper{}
}

// Map maps an event document to a read projection
func (m *EventProjectionMapper) Map(doc bson.M) bson.M {
	if doc == nil {
		return nil
	}

	// Extract fields from source document
	projection := bson.M{}

	// Copy ID
	if id, ok := doc["_id"]; ok {
		projection["_id"] = id
	}

	// Copy basic fields
	copyField(doc, projection, "type")
	copyField(doc, projection, "source")
	copyField(doc, projection, "specversion")
	copyField(doc, projection, "datacontenttype")
	copyField(doc, projection, "dataschema")
	copyField(doc, projection, "subject")
	copyField(doc, projection, "data")
	copyField(doc, projection, "idempotencyKey")

	// Copy time field
	copyField(doc, projection, "time")

	// Copy context data
	if contextData, ok := doc["contextData"].(bson.M); ok {
		projContext := bson.M{}
		copyField(contextData, projContext, "clientId")
		copyField(contextData, projContext, "applicationCode")
		copyField(contextData, projContext, "principalId")
		copyField(contextData, projContext, "correlationId")
		copyField(contextData, projContext, "traceId")
		projection["contextData"] = projContext
	}

	// Add denormalized fields for efficient querying
	if contextData, ok := doc["contextData"].(bson.M); ok {
		if clientId, ok := contextData["clientId"]; ok {
			projection["clientId"] = clientId
		}
		if applicationCode, ok := contextData["applicationCode"]; ok {
			projection["applicationCode"] = applicationCode
		}
	}

	// Copy audit timestamps
	copyField(doc, projection, "createdAt")
	copyField(doc, projection, "updatedAt")

	// Add projection timestamp
	projection["projectedAt"] = primitive.NewDateTimeFromTime(time.Now())

	return projection
}

// DispatchJobProjectionMapper maps dispatch job documents to read projections
type DispatchJobProjectionMapper struct{}

// NewDispatchJobProjectionMapper creates a new dispatch job projection mapper
func NewDispatchJobProjectionMapper() *DispatchJobProjectionMapper {
	return &DispatchJobProjectionMapper{}
}

// Map maps a dispatch job document to a read projection
func (m *DispatchJobProjectionMapper) Map(doc bson.M) bson.M {
	if doc == nil {
		return nil
	}

	projection := bson.M{}

	// Copy ID
	if id, ok := doc["_id"]; ok {
		projection["_id"] = id
	}

	// Copy basic fields
	copyField(doc, projection, "eventId")
	copyField(doc, projection, "eventType")
	copyField(doc, projection, "subscriptionId")
	copyField(doc, projection, "dispatchPoolId")
	copyField(doc, projection, "status")
	copyField(doc, projection, "targetUrl")
	copyField(doc, projection, "payload")
	copyField(doc, projection, "contentType")
	copyField(doc, projection, "messageGroup")

	// Copy scheduling fields
	copyField(doc, projection, "scheduledFor")
	copyField(doc, projection, "startedAt")
	copyField(doc, projection, "completedAt")

	// Copy retry configuration
	copyField(doc, projection, "maxRetries")
	copyField(doc, projection, "attemptCount")
	copyField(doc, projection, "timeoutSeconds")

	// Copy metadata
	if metadata, ok := doc["metadata"].(bson.M); ok {
		projMetadata := bson.M{}
		copyField(metadata, projMetadata, "clientId")
		copyField(metadata, projMetadata, "applicationCode")
		copyField(metadata, projMetadata, "correlationId")
		copyField(metadata, projMetadata, "traceId")
		projection["metadata"] = projMetadata

		// Denormalize for efficient querying
		if clientId, ok := metadata["clientId"]; ok {
			projection["clientId"] = clientId
		}
		if applicationCode, ok := metadata["applicationCode"]; ok {
			projection["applicationCode"] = applicationCode
		}
	}

	// Copy attempts array for detailed history
	if attempts, ok := doc["attempts"].(primitive.A); ok {
		projAttempts := make([]bson.M, 0, len(attempts))
		for _, attempt := range attempts {
			if attemptDoc, ok := attempt.(bson.M); ok {
				projAttempt := bson.M{}
				copyField(attemptDoc, projAttempt, "attemptNumber")
				copyField(attemptDoc, projAttempt, "startedAt")
				copyField(attemptDoc, projAttempt, "completedAt")
				copyField(attemptDoc, projAttempt, "status")
				copyField(attemptDoc, projAttempt, "statusCode")
				copyField(attemptDoc, projAttempt, "errorMessage")
				copyField(attemptDoc, projAttempt, "durationMs")
				projAttempts = append(projAttempts, projAttempt)
			}
		}
		projection["attempts"] = projAttempts
	}

	// Copy last attempt summary
	copyField(doc, projection, "lastAttemptAt")
	copyField(doc, projection, "lastStatusCode")
	copyField(doc, projection, "lastErrorMessage")

	// Copy audit timestamps
	copyField(doc, projection, "createdAt")
	copyField(doc, projection, "updatedAt")

	// Add projection timestamp
	projection["projectedAt"] = primitive.NewDateTimeFromTime(time.Now())

	return projection
}

// copyField copies a field from source to destination if it exists
func copyField(src, dst bson.M, field string) {
	if val, ok := src[field]; ok {
		dst[field] = val
	}
}
