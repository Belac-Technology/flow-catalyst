package eventtype

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

// Repository handles event type persistence
type Repository struct {
	collection *mongo.Collection
}

// NewRepository creates a new event type repository
func NewRepository(db *mongo.Database) *Repository {
	return &Repository{
		collection: db.Collection("event_types"),
	}
}

// FindAll finds all event types
func (r *Repository) FindAll(ctx context.Context) ([]*EventType, error) {
	cursor, err := r.collection.Find(ctx, bson.M{}, options.Find().SetSort(bson.M{"code": 1}))
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var types []*EventType
	if err := cursor.All(ctx, &types); err != nil {
		return nil, err
	}
	return types, nil
}

// FindByID finds an event type by ID
func (r *Repository) FindByID(ctx context.Context, id string) (*EventType, error) {
	var et EventType
	err := r.collection.FindOne(ctx, bson.M{"_id": id}).Decode(&et)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, nil
		}
		return nil, err
	}
	return &et, nil
}

// FindByCode finds an event type by code
func (r *Repository) FindByCode(ctx context.Context, code string) (*EventType, error) {
	var et EventType
	err := r.collection.FindOne(ctx, bson.M{"code": code}).Decode(&et)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, nil
		}
		return nil, err
	}
	return &et, nil
}

// Insert inserts a new event type
func (r *Repository) Insert(ctx context.Context, et *EventType) error {
	et.ID = tsid.Generate()
	et.CreatedAt = time.Now()
	et.UpdatedAt = time.Now()
	_, err := r.collection.InsertOne(ctx, et)
	return err
}

// Update updates an event type
func (r *Repository) Update(ctx context.Context, et *EventType) error {
	et.UpdatedAt = time.Now()
	_, err := r.collection.UpdateByID(ctx, et.ID, bson.M{"$set": et})
	return err
}

// Delete deletes an event type
func (r *Repository) Delete(ctx context.Context, id string) error {
	_, err := r.collection.DeleteOne(ctx, bson.M{"_id": id})
	return err
}

// HTTP Handlers

func (r *Repository) ListHandler(w http.ResponseWriter, req *http.Request) {
	types, err := r.FindAll(req.Context())
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, types)
}

func (r *Repository) CreateHandler(w http.ResponseWriter, req *http.Request) {
	var et EventType
	if err := decodeJSON(req, &et); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}
	et.Status = EventTypeStatusCurrent
	if err := r.Insert(req.Context(), &et); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusCreated, et)
}

func (r *Repository) GetHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	et, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if et == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}
	writeJSON(w, http.StatusOK, et)
}

func (r *Repository) UpdateHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	existing, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if existing == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}

	var et EventType
	if err := decodeJSON(req, &et); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}
	et.ID = id
	et.CreatedAt = existing.CreatedAt
	if err := r.Update(req.Context(), &et); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, et)
}

func (r *Repository) DeleteHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	if err := r.Delete(req.Context(), id); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ArchiveHandler handles POST /event-types/{id}/archive
func (r *Repository) ArchiveHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	et, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if et == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}

	et.Status = EventTypeStatusArchived
	if err := r.Update(req.Context(), et); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, et)
}

// === Schema Management Handlers ===

// AddSchemaRequest represents a request to add a schema version
type AddSchemaRequest struct {
	Version    string     `json:"version"`
	MimeType   string     `json:"mimeType"`
	Schema     string     `json:"schema"`
	SchemaType SchemaType `json:"schemaType"`
}

// ListSchemasHandler handles GET /event-types/{id}/schemas
func (r *Repository) ListSchemasHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	et, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if et == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}

	schemas := et.SpecVersions
	if schemas == nil {
		schemas = []SpecVersion{}
	}
	writeJSON(w, http.StatusOK, schemas)
}

// GetSchemaHandler handles GET /event-types/{id}/schemas/{version}
func (r *Repository) GetSchemaHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	version := chi.URLParam(req, "version")

	et, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if et == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}

	sv := et.FindSpecVersion(version)
	if sv == nil {
		http.Error(w, "Schema version not found", http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, sv)
}

// AddSchemaHandler handles POST /event-types/{id}/schemas
func (r *Repository) AddSchemaHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")

	var addReq AddSchemaRequest
	if err := decodeJSON(req, &addReq); err != nil {
		http.Error(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if addReq.Version == "" {
		http.Error(w, "Version is required", http.StatusBadRequest)
		return
	}
	if addReq.Schema == "" {
		http.Error(w, "Schema is required", http.StatusBadRequest)
		return
	}
	if addReq.SchemaType == "" {
		addReq.SchemaType = SchemaTypeJSONSchema
	}
	if addReq.MimeType == "" {
		addReq.MimeType = "application/json"
	}

	et, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if et == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}

	// Check if event type is archived
	if et.IsArchived() {
		http.Error(w, "Cannot add schema to archived event type", http.StatusConflict)
		return
	}

	// Check for duplicate version
	if et.HasVersion(addReq.Version) {
		http.Error(w, "Schema version already exists", http.StatusConflict)
		return
	}

	// Create new spec version
	now := time.Now()
	sv := SpecVersion{
		Version:    addReq.Version,
		MimeType:   addReq.MimeType,
		Schema:     addReq.Schema,
		SchemaType: addReq.SchemaType,
		Status:     SpecVersionStatusFinalising,
		CreatedAt:  now,
		UpdatedAt:  now,
	}

	et.AddSpecVersion(sv)
	if err := r.Update(req.Context(), et); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusCreated, sv)
}

// FinaliseSchemaHandler handles POST /event-types/{id}/schemas/{version}/finalise
func (r *Repository) FinaliseSchemaHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	version := chi.URLParam(req, "version")

	et, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if et == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}

	sv := et.FindSpecVersion(version)
	if sv == nil {
		http.Error(w, "Schema version not found", http.StatusNotFound)
		return
	}

	if sv.Status != SpecVersionStatusFinalising {
		http.Error(w, "Only finalising schemas can be finalised", http.StatusConflict)
		return
	}

	// Deprecate any existing current version
	for i := range et.SpecVersions {
		if et.SpecVersions[i].Status == SpecVersionStatusCurrent {
			et.SpecVersions[i].Status = SpecVersionStatusDeprecated
			et.SpecVersions[i].UpdatedAt = time.Now()
		}
	}

	// Mark this version as current
	sv.Status = SpecVersionStatusCurrent
	sv.UpdatedAt = time.Now()

	if err := r.Update(req.Context(), et); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, sv)
}

// DeprecateSchemaHandler handles POST /event-types/{id}/schemas/{version}/deprecate
func (r *Repository) DeprecateSchemaHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	version := chi.URLParam(req, "version")

	et, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if et == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}

	sv := et.FindSpecVersion(version)
	if sv == nil {
		http.Error(w, "Schema version not found", http.StatusNotFound)
		return
	}

	if sv.Status == SpecVersionStatusDeprecated {
		http.Error(w, "Schema is already deprecated", http.StatusConflict)
		return
	}

	sv.Status = SpecVersionStatusDeprecated
	sv.UpdatedAt = time.Now()

	if err := r.Update(req.Context(), et); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, sv)
}

// DeleteSchemaHandler handles DELETE /event-types/{id}/schemas/{version}
func (r *Repository) DeleteSchemaHandler(w http.ResponseWriter, req *http.Request) {
	id := chi.URLParam(req, "id")
	version := chi.URLParam(req, "version")

	et, err := r.FindByID(req.Context(), id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if et == nil {
		http.Error(w, "Event type not found", http.StatusNotFound)
		return
	}

	sv := et.FindSpecVersion(version)
	if sv == nil {
		http.Error(w, "Schema version not found", http.StatusNotFound)
		return
	}

	// Only allow deletion of finalising (draft) schemas
	if sv.Status != SpecVersionStatusFinalising {
		http.Error(w, "Only draft (finalising) schemas can be deleted", http.StatusConflict)
		return
	}

	// Remove the version from the array
	newVersions := make([]SpecVersion, 0, len(et.SpecVersions)-1)
	for _, v := range et.SpecVersions {
		if v.Version != version {
			newVersions = append(newVersions, v)
		}
	}
	et.SpecVersions = newVersions

	if err := r.Update(req.Context(), et); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// Helper functions
func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

func decodeJSON(r *http.Request, v interface{}) error {
	return json.NewDecoder(r.Body).Decode(v)
}
