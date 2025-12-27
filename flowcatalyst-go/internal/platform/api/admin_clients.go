package api

import (
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/rs/zerolog/log"

	"go.flowcatalyst.tech/internal/platform/client"
)

// ClientAdminHandler handles client administration endpoints
type ClientAdminHandler struct {
	repo *client.Repository
}

// NewClientAdminHandler creates a new client admin handler
func NewClientAdminHandler(repo *client.Repository) *ClientAdminHandler {
	return &ClientAdminHandler{repo: repo}
}

// Routes returns the router for client admin endpoints
func (h *ClientAdminHandler) Routes() chi.Router {
	r := chi.NewRouter()

	r.Get("/", h.List)
	r.Post("/", h.Create)
	r.Get("/{id}", h.Get)
	r.Put("/{id}", h.Update)
	r.Delete("/{id}", h.Delete)
	r.Post("/{id}/suspend", h.Suspend)
	r.Post("/{id}/activate", h.Activate)
	r.Post("/{id}/notes", h.AddNote)

	return r
}

// ClientDTO represents a client for API responses
type ClientDTO struct {
	ID              string              `json:"id"`
	Name            string              `json:"name"`
	Identifier      string              `json:"identifier"`
	Status          client.ClientStatus `json:"status"`
	StatusReason    string              `json:"statusReason,omitempty"`
	StatusChangedAt string              `json:"statusChangedAt,omitempty"`
	CreatedAt       string              `json:"createdAt"`
	UpdatedAt       string              `json:"updatedAt"`
}

// CreateClientRequest represents a request to create a client
type CreateClientRequest struct {
	Name       string `json:"name"`
	Identifier string `json:"identifier"`
}

// UpdateClientRequest represents a request to update a client
type UpdateClientRequest struct {
	Name string `json:"name"`
}

// SuspendClientRequest represents a request to suspend a client
type SuspendClientRequest struct {
	Reason string `json:"reason"`
}

// AddNoteRequest represents a request to add a note
type AddNoteRequest struct {
	Text     string `json:"text"`
	Category string `json:"category,omitempty"`
}

// List handles GET /api/admin/platform/clients
func (h *ClientAdminHandler) List(w http.ResponseWriter, r *http.Request) {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	pageSize, _ := strconv.Atoi(r.URL.Query().Get("pageSize"))
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	skip := int64((page - 1) * pageSize)
	clients, err := h.repo.FindAll(r.Context(), skip, int64(pageSize))
	if err != nil {
		log.Error().Err(err).Msg("Failed to list clients")
		WriteInternalError(w, "Failed to list clients")
		return
	}

	// Convert to DTOs
	dtos := make([]ClientDTO, len(clients))
	for i, c := range clients {
		dtos[i] = toClientDTO(c)
	}

	WriteJSON(w, http.StatusOK, dtos)
}

// Get handles GET /api/admin/platform/clients/{id}
func (h *ClientAdminHandler) Get(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	c, err := h.repo.FindByID(r.Context(), id)
	if err != nil {
		if err == client.ErrNotFound {
			WriteNotFound(w, "Client not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get client")
		WriteInternalError(w, "Failed to get client")
		return
	}

	WriteJSON(w, http.StatusOK, toClientDTO(c))
}

// Create handles POST /api/admin/platform/clients
func (h *ClientAdminHandler) Create(w http.ResponseWriter, r *http.Request) {
	var req CreateClientRequest
	if err := DecodeJSON(r, &req); err != nil {
		WriteBadRequest(w, "Invalid request body")
		return
	}

	if req.Name == "" {
		WriteBadRequest(w, "Name is required")
		return
	}
	if req.Identifier == "" {
		WriteBadRequest(w, "Identifier is required")
		return
	}

	c := &client.Client{
		Name:       req.Name,
		Identifier: req.Identifier,
		Status:     client.ClientStatusActive,
	}

	if err := h.repo.Insert(r.Context(), c); err != nil {
		if err == client.ErrDuplicateIdentifier {
			WriteConflict(w, "Identifier already exists")
			return
		}
		log.Error().Err(err).Msg("Failed to create client")
		WriteInternalError(w, "Failed to create client")
		return
	}

	WriteJSON(w, http.StatusCreated, toClientDTO(c))
}

// Update handles PUT /api/admin/platform/clients/{id}
func (h *ClientAdminHandler) Update(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	var req UpdateClientRequest
	if err := DecodeJSON(r, &req); err != nil {
		WriteBadRequest(w, "Invalid request body")
		return
	}

	c, err := h.repo.FindByID(r.Context(), id)
	if err != nil {
		if err == client.ErrNotFound {
			WriteNotFound(w, "Client not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get client")
		WriteInternalError(w, "Failed to get client")
		return
	}

	if req.Name != "" {
		c.Name = req.Name
	}

	if err := h.repo.Update(r.Context(), c); err != nil {
		log.Error().Err(err).Str("id", id).Msg("Failed to update client")
		WriteInternalError(w, "Failed to update client")
		return
	}

	WriteJSON(w, http.StatusOK, toClientDTO(c))
}

// Delete handles DELETE /api/admin/platform/clients/{id}
func (h *ClientAdminHandler) Delete(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	if err := h.repo.Delete(r.Context(), id); err != nil {
		if err == client.ErrNotFound {
			WriteNotFound(w, "Client not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to delete client")
		WriteInternalError(w, "Failed to delete client")
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// Suspend handles POST /api/admin/platform/clients/{id}/suspend
func (h *ClientAdminHandler) Suspend(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	var req SuspendClientRequest
	if err := DecodeJSON(r, &req); err != nil {
		WriteBadRequest(w, "Invalid request body")
		return
	}

	if err := h.repo.UpdateStatus(r.Context(), id, client.ClientStatusSuspended, req.Reason); err != nil {
		if err == client.ErrNotFound {
			WriteNotFound(w, "Client not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to suspend client")
		WriteInternalError(w, "Failed to suspend client")
		return
	}

	c, _ := h.repo.FindByID(r.Context(), id)
	WriteJSON(w, http.StatusOK, toClientDTO(c))
}

// Activate handles POST /api/admin/platform/clients/{id}/activate
func (h *ClientAdminHandler) Activate(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	if err := h.repo.UpdateStatus(r.Context(), id, client.ClientStatusActive, ""); err != nil {
		if err == client.ErrNotFound {
			WriteNotFound(w, "Client not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to activate client")
		WriteInternalError(w, "Failed to activate client")
		return
	}

	c, _ := h.repo.FindByID(r.Context(), id)
	WriteJSON(w, http.StatusOK, toClientDTO(c))
}

// AddNote handles POST /api/admin/platform/clients/{id}/notes
func (h *ClientAdminHandler) AddNote(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	var req AddNoteRequest
	if err := DecodeJSON(r, &req); err != nil {
		WriteBadRequest(w, "Invalid request body")
		return
	}

	if req.Text == "" {
		WriteBadRequest(w, "Text is required")
		return
	}

	p := GetPrincipal(r.Context())
	addedBy := ""
	if p != nil {
		addedBy = p.ID
	}

	note := client.ClientNote{
		Text:     req.Text,
		Category: req.Category,
		AddedBy:  addedBy,
	}

	if err := h.repo.AddNote(r.Context(), id, note); err != nil {
		if err == client.ErrNotFound {
			WriteNotFound(w, "Client not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to add note")
		WriteInternalError(w, "Failed to add note")
		return
	}

	c, _ := h.repo.FindByID(r.Context(), id)
	WriteJSON(w, http.StatusOK, toClientDTO(c))
}

// toClientDTO converts a Client to ClientDTO
func toClientDTO(c *client.Client) ClientDTO {
	dto := ClientDTO{
		ID:         c.ID,
		Name:       c.Name,
		Identifier: c.Identifier,
		Status:     c.Status,
		CreatedAt:  c.CreatedAt.Format("2006-01-02T15:04:05Z"),
		UpdatedAt:  c.UpdatedAt.Format("2006-01-02T15:04:05Z"),
	}

	if c.StatusReason != "" {
		dto.StatusReason = c.StatusReason
	}
	if !c.StatusChangedAt.IsZero() {
		dto.StatusChangedAt = c.StatusChangedAt.Format("2006-01-02T15:04:05Z")
	}

	return dto
}
