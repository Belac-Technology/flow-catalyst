package outbox

import (
	"context"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

// MongoRepository implements Repository for MongoDB
type MongoRepository struct {
	db     *mongo.Database
	config *RepositoryConfig
}

// NewMongoRepository creates a new MongoDB outbox repository
func NewMongoRepository(db *mongo.Database, config *RepositoryConfig) *MongoRepository {
	if config == nil {
		config = DefaultRepositoryConfig()
	}
	return &MongoRepository{
		db:     db,
		config: config,
	}
}

// GetTableName returns the collection name for the item type
func (r *MongoRepository) GetTableName(itemType OutboxItemType) string {
	switch itemType {
	case OutboxItemTypeEvent:
		return r.config.EventsTable
	case OutboxItemTypeDispatchJob:
		return r.config.DispatchJobsTable
	default:
		return r.config.EventsTable
	}
}

// getCollection returns the MongoDB collection for the item type
func (r *MongoRepository) getCollection(itemType OutboxItemType) *mongo.Collection {
	return r.db.Collection(r.GetTableName(itemType))
}

// FetchAndLockPending atomically fetches pending items and marks them as PROCESSING.
// MongoDB uses findOneAndUpdate which is naturally atomic.
// We loop to fetch multiple items.
func (r *MongoRepository) FetchAndLockPending(ctx context.Context, itemType OutboxItemType, limit int) ([]*OutboxItem, error) {
	collection := r.getCollection(itemType)
	items := make([]*OutboxItem, 0, limit)

	filter := bson.M{"status": OutboxStatusPending}
	update := bson.M{
		"$set": bson.M{
			"status":      OutboxStatusProcessing,
			"processedAt": time.Now(),
		},
	}
	opts := options.FindOneAndUpdate().
		SetSort(bson.D{{Key: "messageGroup", Value: 1}, {Key: "createdAt", Value: 1}}).
		SetReturnDocument(options.After)

	for i := 0; i < limit; i++ {
		var item OutboxItem
		err := collection.FindOneAndUpdate(ctx, filter, update, opts).Decode(&item)
		if err != nil {
			if err == mongo.ErrNoDocuments {
				// No more pending items
				break
			}
			return items, err
		}
		items = append(items, &item)
	}

	return items, nil
}

// MarkCompleted updates items to COMPLETED status
func (r *MongoRepository) MarkCompleted(ctx context.Context, itemType OutboxItemType, ids []string) error {
	if len(ids) == 0 {
		return nil
	}

	collection := r.getCollection(itemType)
	filter := bson.M{"_id": bson.M{"$in": ids}}
	update := bson.M{
		"$set": bson.M{
			"status":      OutboxStatusCompleted,
			"processedAt": time.Now(),
		},
	}

	_, err := collection.UpdateMany(ctx, filter, update)
	return err
}

// MarkFailed marks items as FAILED with error message
func (r *MongoRepository) MarkFailed(ctx context.Context, itemType OutboxItemType, ids []string, errorMessage string) error {
	if len(ids) == 0 {
		return nil
	}

	collection := r.getCollection(itemType)
	filter := bson.M{"_id": bson.M{"$in": ids}}
	update := bson.M{
		"$set": bson.M{
			"status":       OutboxStatusFailed,
			"errorMessage": errorMessage,
			"processedAt":  time.Now(),
		},
	}

	_, err := collection.UpdateMany(ctx, filter, update)
	return err
}

// ScheduleRetry increments retryCount and resets to PENDING
func (r *MongoRepository) ScheduleRetry(ctx context.Context, itemType OutboxItemType, ids []string) error {
	if len(ids) == 0 {
		return nil
	}

	collection := r.getCollection(itemType)
	filter := bson.M{"_id": bson.M{"$in": ids}}
	update := bson.M{
		"$set": bson.M{
			"status": OutboxStatusPending,
		},
		"$inc": bson.M{
			"retryCount": 1,
		},
	}

	_, err := collection.UpdateMany(ctx, filter, update)
	return err
}

// RecoverStuckItems resets stuck PROCESSING items to PENDING
func (r *MongoRepository) RecoverStuckItems(ctx context.Context, itemType OutboxItemType, timeoutSeconds int) (int64, error) {
	collection := r.getCollection(itemType)

	threshold := time.Now().Add(-time.Duration(timeoutSeconds) * time.Second)
	filter := bson.M{
		"status":      OutboxStatusProcessing,
		"processedAt": bson.M{"$lt": threshold},
	}
	update := bson.M{
		"$set": bson.M{
			"status": OutboxStatusPending,
		},
	}

	result, err := collection.UpdateMany(ctx, filter, update)
	if err != nil {
		return 0, err
	}
	return result.ModifiedCount, nil
}

// EnsureIndexes creates the necessary indexes for the outbox collections
func (r *MongoRepository) EnsureIndexes(ctx context.Context) error {
	for _, itemType := range []OutboxItemType{OutboxItemTypeEvent, OutboxItemTypeDispatchJob} {
		collection := r.getCollection(itemType)

		// Index for fetching pending items in FIFO order
		_, err := collection.Indexes().CreateOne(ctx, mongo.IndexModel{
			Keys: bson.D{
				{Key: "status", Value: 1},
				{Key: "messageGroup", Value: 1},
				{Key: "createdAt", Value: 1},
			},
		})
		if err != nil {
			return err
		}
	}
	return nil
}
