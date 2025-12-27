package role

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

// Role represents an authentication role
type Role struct {
	ID          string    `bson:"_id" json:"id"`
	Code        string    `bson:"code" json:"code"`
	Name        string    `bson:"name" json:"name"`
	Description string    `bson:"description,omitempty" json:"description,omitempty"`
	Scope       string    `bson:"scope" json:"scope"` // ANCHOR, PARTNER, CLIENT
	Permissions []string  `bson:"permissions" json:"permissions"`
	BuiltIn     bool      `bson:"builtIn" json:"builtIn"`
	CreatedAt   time.Time `bson:"createdAt" json:"createdAt"`
	UpdatedAt   time.Time `bson:"updatedAt" json:"updatedAt"`
}

// Repository handles role persistence
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a new role repository
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{
		collection: db.Collection("auth_roles"),
	}
}

// FindAll finds all roles
func (r *Repository) FindAll(ctx context.Context) ([]*Role, error) {
	cursor, err := r.collection.Find(ctx, bson.M{}, options.Find().SetSort(bson.M{"code": 1}))
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var roles []*Role
	if err := cursor.All(ctx, &roles); err != nil {
		return nil, err
	}
	return roles, nil
}

// FindByID finds a role by ID
func (r *Repository) FindByID(ctx context.Context, id string) (*Role, error) {
	var role Role
	err := r.collection.FindOne(ctx, bson.M{"_id": id}).Decode(&role)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, nil
		}
		return nil, err
	}
	return &role, nil
}

// FindByCode finds a role by code
func (r *Repository) FindByCode(ctx context.Context, code string) (*Role, error) {
	var role Role
	err := r.collection.FindOne(ctx, bson.M{"code": code}).Decode(&role)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, nil
		}
		return nil, err
	}
	return &role, nil
}

// Insert inserts a new role
func (r *Repository) Insert(ctx context.Context, role *Role) error {
	role.ID = tsid.Generate()
	role.CreatedAt = time.Now()
	role.UpdatedAt = time.Now()
	_, err := r.collection.InsertOne(ctx, role)
	return err
}

// Update updates a role
func (r *Repository) Update(ctx context.Context, role *Role) error {
	role.UpdatedAt = time.Now()
	_, err := r.collection.UpdateByID(ctx, role.ID, bson.M{"$set": role})
	return err
}

// Delete deletes a role
func (r *Repository) Delete(ctx context.Context, id string) error {
	_, err := r.collection.DeleteOne(ctx, bson.M{"_id": id})
	return err
}

// HTTP Handlers

func (r *Repository) ListHandler(w http.ResponseWriter, req *http.Request) {
	roles, err := r.FindAll(req.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, roles)
}

func (r *Repository) CreateHandler(w http.ResponseWriter, req *http.Request) {
	var role Role
	if err := json.NewDecoder(req.Body).Decode(&role); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}
	if role.Permissions == nil {
		role.Permissions = []string{}
	}
	if err := r.Insert(req.Context(), &role); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusCreated, role)
}

func (r *Repository) GetHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	role, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if role == nil {
		http.Error(w, "Role not found", http.StatusNotFound)
		return
	}
	writeJSON(w, http.StatusOK, role)
}

func (r *Repository) UpdateHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	existing, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if existing == nil {
		http.Error(w, "Role not found", http.StatusNotFound)
		return
	}
	if existing.BuiltIn {
		http.Error(w, "Cannot modify built-in role", http.StatusForbidden)
		return
	}

	var role Role
	if err := json.NewDecoder(req.Body).Decode(&role); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}
	role.ID = id
	role.CreatedAt = existing.CreatedAt
	role.BuiltIn = existing.BuiltIn
	if err := r.Update(req.Context(), &role); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, role)
}

func (r *Repository) DeleteHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	existing, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if existing != nil && existing.BuiltIn {
		http.Error(w, "Cannot delete built-in role", http.StatusForbidden)
		return
	}
	if err := r.Delete(req.Context(), id); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}
