package permission

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"go.flowcatalyst.tech/internal/common/tsid"
)

// Permission represents a permission
type Permission struct {
	ID          string    `bson:"_id" json:"id"`
	Code        string    `bson:"code" json:"code"`
	Name        string    `bson:"name" json:"name"`
	Description string    `bson:"description,omitempty" json:"description,omitempty"`
	Category    string    `bson:"category,omitempty" json:"category,omitempty"`
	CreatedAt   time.Time `bson:"createdAt" json:"createdAt"`
	UpdatedAt   time.Time `bson:"updatedAt" json:"updatedAt"`
}

// Repository handles permission persistence
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a new permission repository
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{
		collection: db.Collection("auth_permissions"),
	}
}

// FindAll finds all permissions
func (r *Repository) FindAll(ctx context.Context) ([]*Permission, error) {
	cursor, err := r.collection.Find(ctx, bson.M{}, options.Find().SetSort(bson.M{"code": 1}))
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var permissions []*Permission
	if err := cursor.All(ctx, &permissions); err != nil {
		return nil, err
	}
	return permissions, nil
}

// FindByID finds a permission by ID
func (r *Repository) FindByID(ctx context.Context, id string) (*Permission, error) {
	var perm Permission
	err := r.collection.FindOne(ctx, bson.M{"_id": id}).Decode(&perm)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, nil
		}
		return nil, err
	}
	return &perm, nil
}

// FindByCode finds a permission by code
func (r *Repository) FindByCode(ctx context.Context, code string) (*Permission, error) {
	var perm Permission
	err := r.collection.FindOne(ctx, bson.M{"code": code}).Decode(&perm)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, nil
		}
		return nil, err
	}
	return &perm, nil
}

// Insert inserts a new permission
func (r *Repository) Insert(ctx context.Context, perm *Permission) error {
	perm.ID = tsid.Generate()
	perm.CreatedAt = time.Now()
	perm.UpdatedAt = time.Now()
	_, err := r.collection.InsertOne(ctx, perm)
	return err
}

// HTTP Handlers

func (r *Repository) ListHandler(w http.ResponseWriter, req *http.Request) {
	permissions, err := r.FindAll(req.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(permissions)
}

func (r *Repository) GetHandler(w http.ResponseWriter, req *http.Request) {
	code := chi.URLParam(req, "code")

	perm, err := r.FindByCode(req.Context(), code)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if perm == nil {
		http.Error(w, "Permission not found", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(perm)
}
