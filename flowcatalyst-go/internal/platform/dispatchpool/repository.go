package dispatchpool

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"go.flowcatalyst.tech/internal/common/tsid"
)

var (
	ErrNotFound      = errors.New("dispatch pool not found")
	ErrDuplicateCode = errors.New("dispatch pool code already exists")
)

// Repository provides access to dispatch pool data
type Repository struct {
	pools *mongo.Collection
}

// NewRepository creates a new dispatch pool repository
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{
		pools: db.Collection("dispatch_pools"),
	}
}

// FindByID finds a dispatch pool by ID
func (r *Repository) FindByID(ctx context.Context, id string) (*DispatchPool, error) {
	var pool DispatchPool
	err := r.pools.FindOne(ctx, bson.M{"_id": id}).Decode(&pool)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &pool, nil
}

// FindByCode finds a dispatch pool by code
func (r *Repository) FindByCode(ctx context.Context, code string) (*DispatchPool, error) {
	var pool DispatchPool
	err := r.pools.FindOne(ctx, bson.M{"code": code}).Decode(&pool)
	if err != nil {
		if errors.Is(err, mongo.ErrNoDocuments) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &pool, nil
}

// FindAll finds all dispatch pools
func (r *Repository) FindAll(ctx context.Context) ([]*DispatchPool, error) {
	opts := options.Find().SetSort(bson.D{{Key: "code", Value: 1}})

	cursor, err := r.pools.Find(ctx, bson.M{}, opts)
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

// FindAllEnabled finds all enabled dispatch pools
// Deprecated: Use FindAllActive instead
func (r *Repository) FindAllEnabled(ctx context.Context) ([]*DispatchPool, error) {
	// Support both old 'enabled' field and new 'status' field for backwards compatibility
	filter := bson.M{
		"$or": []bson.M{
			{"status": DispatchPoolStatusActive},
			{"enabled": true, "status": bson.M{"$exists": false}},
		},
	}
	opts := options.Find().SetSort(bson.D{{Key: "code", Value: 1}})

	cursor, err := r.pools.Find(ctx, filter, opts)
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

// FindAllActive finds all active dispatch pools
func (r *Repository) FindAllActive(ctx context.Context) ([]*DispatchPool, error) {
	filter := bson.M{"status": DispatchPoolStatusActive}
	opts := options.Find().SetSort(bson.D{{Key: "code", Value: 1}})

	cursor, err := r.pools.Find(ctx, filter, opts)
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

// FindByStatus finds pools by status
func (r *Repository) FindByStatus(ctx context.Context, status DispatchPoolStatus) ([]*DispatchPool, error) {
	filter := bson.M{"status": status}
	opts := options.Find().SetSort(bson.D{{Key: "code", Value: 1}})

	cursor, err := r.pools.Find(ctx, filter, opts)
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

// FindAnchorLevel finds anchor-level pools (not bound to any client)
func (r *Repository) FindAnchorLevel(ctx context.Context) ([]*DispatchPool, error) {
	filter := bson.M{
		"$or": []bson.M{
			{"clientId": nil},
			{"clientId": ""},
			{"clientId": bson.M{"$exists": false}},
		},
	}
	opts := options.Find().SetSort(bson.D{{Key: "code", Value: 1}})

	cursor, err := r.pools.Find(ctx, filter, opts)
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

// FindAllNonArchived finds all pools that are not archived
func (r *Repository) FindAllNonArchived(ctx context.Context) ([]*DispatchPool, error) {
	filter := bson.M{"status": bson.M{"$ne": DispatchPoolStatusArchived}}
	opts := options.Find().SetSort(bson.D{{Key: "code", Value: 1}})

	cursor, err := r.pools.Find(ctx, filter, opts)
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

// FindByClientID finds dispatch pools for a specific client
func (r *Repository) FindByClientID(ctx context.Context, clientID string) ([]*DispatchPool, error) {
	filter := bson.M{"clientId": clientID}
	opts := options.Find().SetSort(bson.D{{Key: "code", Value: 1}})

	cursor, err := r.pools.Find(ctx, filter, opts)
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

// Insert creates a new dispatch pool
func (r *Repository) Insert(ctx context.Context, pool *DispatchPool) error {
	if pool.ID == "" {
		pool.ID = tsid.Generate()
	}
	now := time.Now()
	pool.CreatedAt = now
	pool.UpdatedAt = now

	_, err := r.pools.InsertOne(ctx, pool)
	if mongo.IsDuplicateKeyError(err) {
		return ErrDuplicateCode
	}
	return err
}

// Update updates an existing dispatch pool
func (r *Repository) Update(ctx context.Context, pool *DispatchPool) error {
	pool.UpdatedAt = time.Now()

	result, err := r.pools.ReplaceOne(ctx, bson.M{"_id": pool.ID}, pool)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// UpdateConfig updates pool configuration fields
func (r *Repository) UpdateConfig(ctx context.Context, id string, concurrency, queueCapacity int, rateLimitPerMin *int) error {
	update := bson.M{
		"$set": bson.M{
			"concurrency":     concurrency,
			"queueCapacity":   queueCapacity,
			"rateLimitPerMin": rateLimitPerMin,
			"updatedAt":       time.Now(),
		},
	}

	result, err := r.pools.UpdateOne(ctx, bson.M{"_id": id}, update)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// SetEnabled enables or disables a dispatch pool
// Deprecated: Use SetStatus instead
func (r *Repository) SetEnabled(ctx context.Context, id string, enabled bool) error {
	status := DispatchPoolStatusSuspended
	if enabled {
		status = DispatchPoolStatusActive
	}
	return r.SetStatus(ctx, id, status)
}

// SetStatus updates pool status
func (r *Repository) SetStatus(ctx context.Context, id string, status DispatchPoolStatus) error {
	update := bson.M{
		"$set": bson.M{
			"status":    status,
			"enabled":   status == DispatchPoolStatusActive, // Keep enabled field in sync for backwards compatibility
			"updatedAt": time.Now(),
		},
	}

	result, err := r.pools.UpdateOne(ctx, bson.M{"_id": id}, update)
	if err != nil {
		return err
	}
	if result.MatchedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// Delete removes a dispatch pool
func (r *Repository) Delete(ctx context.Context, id string) error {
	result, err := r.pools.DeleteOne(ctx, bson.M{"_id": id})
	if err != nil {
		return err
	}
	if result.DeletedCount == 0 {
		return ErrNotFound
	}
	return nil
}

// Count returns the total number of dispatch pools
func (r *Repository) Count(ctx context.Context) (int64, error) {
	return r.pools.CountDocuments(ctx, bson.M{})
}

// CountEnabled returns the number of enabled dispatch pools
// Deprecated: Use CountActive instead
func (r *Repository) CountEnabled(ctx context.Context) (int64, error) {
	return r.CountActive(ctx)
}

// CountActive returns the number of active dispatch pools
func (r *Repository) CountActive(ctx context.Context) (int64, error) {
	return r.pools.CountDocuments(ctx, bson.M{"status": DispatchPoolStatusActive})
}

// CountByStatus returns the number of pools with a specific status
func (r *Repository) CountByStatus(ctx context.Context, status DispatchPoolStatus) (int64, error) {
	return r.pools.CountDocuments(ctx, bson.M{"status": status})
}

// ExistsByCode checks if a pool with the given code exists
func (r *Repository) ExistsByCode(ctx context.Context, code string) (bool, error) {
	count, err := r.pools.CountDocuments(ctx, bson.M{"code": code})
	if err != nil {
		return false, err
	}
	return count > 0, nil
}

// === HTTP Handlers ===

// ListHandler handles GET /dispatch-pools
func (r *Repository) ListHandler(w http.ResponseWriter, req *http.Request) {
	pools, err := r.FindAll(req.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, pools)
}

// CreateHandler handles POST /dispatch-pools
func (r *Repository) CreateHandler(w http.ResponseWriter, req *http.Request) {
	var pool DispatchPool
	if err := json.NewDecoder(req.Body).Decode(&pool); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	// Set defaults
	if pool.MediatorType == "" {
		pool.MediatorType = MediatorTypeHTTPWebhook
	}
	if pool.Concurrency <= 0 {
		pool.Concurrency = 10
	}
	if pool.QueueCapacity <= 0 {
		pool.QueueCapacity = 500
	}
	// Default to active status
	if pool.Status == "" {
		pool.Status = DispatchPoolStatusActive
		pool.Enabled = true // Keep backwards compatible
	}

	if err := r.Insert(req.Context(), &pool); err != nil {
		if errors.Is(err, ErrDuplicateCode) {
			http.Error(w, "Pool code already exists", http.StatusConflict)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusCreated, pool)
}

// GetHandler handles GET /dispatch-pools/{id}
func (r *Repository) GetHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	if id == "" {
		http.Error(w, "Missing pool ID", http.StatusBadRequest)
		return
	}

	pool, err := r.FindByID(req.Context(), id)
	if err != nil {
		if errors.Is(err, ErrNotFound) {
			http.Error(w, "Pool not found", http.StatusNotFound)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, pool)
}

// UpdateHandler handles PUT /dispatch-pools/{id}
func (r *Repository) UpdateHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	if id == "" {
		http.Error(w, "Missing pool ID", http.StatusBadRequest)
		return
	}

	var pool DispatchPool
	if err := json.NewDecoder(req.Body).Decode(&pool); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	pool.ID = id

	if err := r.Update(req.Context(), &pool); err != nil {
		if errors.Is(err, ErrNotFound) {
			http.Error(w, "Pool not found", http.StatusNotFound)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, pool)
}

// DeleteHandler handles DELETE /dispatch-pools/{id}
func (r *Repository) DeleteHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	if id == "" {
		http.Error(w, "Missing pool ID", http.StatusBadRequest)
		return
	}

	if err := r.Delete(req.Context(), id); err != nil {
		if errors.Is(err, ErrNotFound) {
			http.Error(w, "Pool not found", http.StatusNotFound)
			return
		}
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// writeJSON writes JSON response
func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}
