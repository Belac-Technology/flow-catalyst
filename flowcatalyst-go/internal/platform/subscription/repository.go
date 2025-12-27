package subscription

import (
	"context"
	"errors"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"go.flowcatalyst.tech/internal/common/tsid"
)

var (
	ErrNotFound      = errors.New("not found")
	ErrDuplicateCode = errors.New("duplicate code")
)

// Repository provides access to subscription and dispatch pool data
type Repository struct {
	subscriptions *mongo.Collection
	dispatchPools *mongo.Collection
}

// NewRepository creates a new subscription repository
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{
		subscriptions: db.Collection("subscriptions"),
		dispatchPools: db.Collection("dispatch_pools"),
	}
}

// === Subscription operations ===

// FindSubscriptionByID finds a subscription by ID
func (r *Repository) FindSubscriptionByID(ctx context.Context, id string) (*Subscription, error) {
	var sub Subscription
	err := r.subscriptions.FindOne(ctx, bson.M{"_id": id}).Decode(&sub)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &sub, nil
}

// FindSubscriptionByCode finds a subscription by code
func (r *Repository) FindSubscriptionByCode(ctx context.Context, code string) (*Subscription, error) {
	var sub Subscription
	err := r.subscriptions.FindOne(ctx, bson.M{"code": code}).Decode(&sub)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &sub, nil
}

// FindSubscriptionsByClient finds all subscriptions for a client
func (r *Repository) FindSubscriptionsByClient(ctx context.Context, clientID string) ([]*Subscription, error) {
	cursor, err := r.subscriptions.Find(ctx, bson.M{"clientId": clientID})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var subs []*Subscription
	if err := cursor.All(ctx, &subs); err != nil {
		return nil, err
	}
	return subs, nil
}

// FindActiveSubscriptions finds all active subscriptions
func (r *Repository) FindActiveSubscriptions(ctx context.Context) ([]*Subscription, error) {
	cursor, err := r.subscriptions.Find(ctx, bson.M{"status": SubscriptionStatusActive})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var subs []*Subscription
	if err := cursor.All(ctx, &subs); err != nil {
		return nil, err
	}
	return subs, nil
}

// FindSubscriptionsByEventType finds all active subscriptions matching an event type
func (r *Repository) FindSubscriptionsByEventType(ctx context.Context, eventTypeCode string) ([]*Subscription, error) {
	filter := bson.M{
		"status":               SubscriptionStatusActive,
		"eventTypes.eventTypeCode": eventTypeCode,
	}

	cursor, err := r.subscriptions.Find(ctx, filter)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var subs []*Subscription
	if err := cursor.All(ctx, &subs); err != nil {
		return nil, err
	}
	return subs, nil
}

// FindAllSubscriptions returns all subscriptions with pagination
func (r *Repository) FindAllSubscriptions(ctx context.Context, skip, limit int64) ([]*Subscription, error) {
	opts := options.Find().
		SetSkip(skip).
		SetLimit(limit).
		SetSort(bson.D{{Key: "name", Value: 1}})

	cursor, err := r.subscriptions.Find(ctx, bson.M{}, opts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var subs []*Subscription
	if err := cursor.All(ctx, &subs); err != nil {
		return nil, err
	}
	return subs, nil
}

// InsertSubscription creates a new subscription
func (r *Repository) InsertSubscription(ctx context.Context, sub *Subscription) error {
	if sub.ID == "" {
		sub.ID = tsid.Generate()
	}
	now := time.Now()
	sub.CreatedAt = now
	sub.UpdatedAt = now

	_, err := r.subscriptions.InsertOne(ctx, sub)
	if mongo.IsDuplicateKeyError(err) {
		return ErrDuplicateCode
	}
	return err
}

// UpdateSubscription updates an existing subscription
func (r *Repository) UpdateSubscription(ctx context.Context, sub *Subscription) error {
	sub.UpdatedAt = time.Now()

	result, err := r.subscriptions.ReplaceOne(ctx, bson.M{"_id": sub.ID}, sub)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// UpdateSubscriptionStatus updates a subscription's status
func (r *Repository) UpdateSubscriptionStatus(ctx context.Context, id string, status SubscriptionStatus) error {
	result, err := r.subscriptions.UpdateOne(ctx,
		bson.M{"_id": id},
		bson.M{"$set": bson.M{
			"status":    status,
			"updatedAt": time.Now(),
		}},
	)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteSubscription removes a subscription
func (r *Repository) DeleteSubscription(ctx context.Context, id string) error {
	result, err := r.subscriptions.DeleteOne(ctx, bson.M{"_id": id})
	if err != nil {
		return err
	}
	if result.DeletedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// === Dispatch Pool operations ===

// FindDispatchPoolByID finds a dispatch pool by ID
func (r *Repository) FindDispatchPoolByID(ctx context.Context, id string) (*DispatchPool, error) {
	var pool DispatchPool
	err := r.dispatchPools.FindOne(ctx, bson.M{"_id": id}).Decode(&pool)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &pool, nil
}

// FindDispatchPoolByCode finds a dispatch pool by code
func (r *Repository) FindDispatchPoolByCode(ctx context.Context, code string) (*DispatchPool, error) {
	var pool DispatchPool
	err := r.dispatchPools.FindOne(ctx, bson.M{"code": code}).Decode(&pool)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &pool, nil
}

// FindDispatchPoolsByClient finds all dispatch pools for a client
func (r *Repository) FindDispatchPoolsByClient(ctx context.Context, clientID string) ([]*DispatchPool, error) {
	cursor, err := r.dispatchPools.Find(ctx, bson.M{"clientId": clientID})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var pools []*DispatchPool
	if err := cursor.All(ctx, &pools); err != nil {
		return nil, err
	}
	return pools, nil
}

// FindActiveDispatchPools finds all active dispatch pools
func (r *Repository) FindActiveDispatchPools(ctx context.Context) ([]*DispatchPool, error) {
	cursor, err := r.dispatchPools.Find(ctx, bson.M{"status": DispatchPoolStatusActive})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var pools []*DispatchPool
	if err := cursor.All(ctx, &pools); err != nil {
		return nil, err
	}
	return pools, nil
}

// FindAllDispatchPools returns all dispatch pools
func (r *Repository) FindAllDispatchPools(ctx context.Context) ([]*DispatchPool, error) {
	cursor, err := r.dispatchPools.Find(ctx, bson.M{})
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var pools []*DispatchPool
	if err := cursor.All(ctx, &pools); err != nil {
		return nil, err
	}
	return pools, nil
}

// InsertDispatchPool creates a new dispatch pool
func (r *Repository) InsertDispatchPool(ctx context.Context, pool *DispatchPool) error {
	if pool.ID == "" {
		pool.ID = tsid.Generate()
	}
	now := time.Now()
	pool.CreatedAt = now
	pool.UpdatedAt = now

	_, err := r.dispatchPools.InsertOne(ctx, pool)
	if mongo.IsDuplicateKeyError(err) {
		return ErrDuplicateCode
	}
	return err
}

// UpdateDispatchPool updates an existing dispatch pool
func (r *Repository) UpdateDispatchPool(ctx context.Context, pool *DispatchPool) error {
	pool.UpdatedAt = time.Now()

	result, err := r.dispatchPools.ReplaceOne(ctx, bson.M{"_id": pool.ID}, pool)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// ArchiveDispatchPool archives a dispatch pool
func (r *Repository) ArchiveDispatchPool(ctx context.Context, id string) error {
	result, err := r.dispatchPools.UpdateOne(ctx,
		bson.M{"_id": id},
		bson.M{"$set": bson.M{
			"status":    DispatchPoolStatusArchived,
			"updatedAt": time.Now(),
		}},
	)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteDispatchPool removes a dispatch pool
func (r *Repository) DeleteDispatchPool(ctx context.Context, id string) error {
	result, err := r.dispatchPools.DeleteOne(ctx, bson.M{"_id": id})
	if err != nil {
		return err
	}
	if result.DeletedCount == 0 {
		return ErrNotFound
	}
	return nil
}
