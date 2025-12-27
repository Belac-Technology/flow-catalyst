package api

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/rs/zerolog/log"

	"go.flowcatalyst.tech/internal/platform/subscription"
)

// SubscriptionHandler handles subscription endpoints
type SubscriptionHandler struct {
	repo *subscription.Repository
}

// NewSubscriptionHandler creates a new subscription handler
func NewSubscriptionHandler(repo *subscription.Repository) *SubscriptionHandler {
	return &SubscriptionHandler{repo: repo}
}

// Routes returns the router for subscription endpoints
func (h *SubscriptionHandler) Routes() chi.Router {
	r := chi.NewRouter()

	r.Get("/", h.List)
	r.Post("/", h.Create)
	r.Get("/{id}", h.Get)
	r.Put("/{id}", h.Update)
	r.Delete("/{id}", h.Delete)
	r.Post("/{id}/pause", h.Pause)
	r.Post("/{id}/resume", h.Resume)

	return r
}

// CreateSubscriptionRequest represents a request to create a subscription
type CreateSubscriptionRequest struct {
	Code             string                       `json:"code"`
	Name             string                       `json:"name"`
	Description      string                       `json:"description,omitempty"`
	EventTypes       []subscription.EventTypeBinding `json:"eventTypes"`
	Target           string                       `json:"target"`
	Queue            string                       `json:"queue,omitempty"`
	CustomConfig     []subscription.ConfigEntry   `json:"customConfig,omitempty"`
	DispatchPoolCode string                       `json:"dispatchPoolCode,omitempty"`
	DelaySeconds     int                          `json:"delaySeconds,omitempty"`
	Mode             subscription.DispatchMode    `json:"mode,omitempty"`
	TimeoutSeconds   int                          `json:"timeoutSeconds,omitempty"`
	DataOnly         bool                         `json:"dataOnly"`
}

// UpdateSubscriptionRequest represents a request to update a subscription
type UpdateSubscriptionRequest struct {
	Name           string                     `json:"name,omitempty"`
	Description    string                     `json:"description,omitempty"`
	Target         string                     `json:"target,omitempty"`
	CustomConfig   []subscription.ConfigEntry `json:"customConfig,omitempty"`
	DelaySeconds   int                        `json:"delaySeconds,omitempty"`
	TimeoutSeconds int                        `json:"timeoutSeconds,omitempty"`
}

// List handles GET /api/subscriptions
func (h *SubscriptionHandler) List(w http.ResponseWriter, r *http.Request) {
	// Get client ID from authenticated principal for filtering
	p := GetPrincipal(r.Context())

	var subs []*subscription.Subscription
	var err error

	if p != nil && !p.IsAnchor() {
		// Non-anchor users can only see their client's subscriptions
		subs, err = h.repo.FindSubscriptionsByClient(r.Context(), p.ClientID)
	} else {
		// Anchor users can see all subscriptions
		subs, err = h.repo.FindAllSubscriptions(r.Context(), 0, 1000)
	}

	if err != nil {
		log.Error().Err(err).Msg("Failed to list subscriptions")
		WriteInternalError(w, "Failed to list subscriptions")
		return
	}

	WriteJSON(w, http.StatusOK, subs)
}

// Get handles GET /api/subscriptions/{id}
func (h *SubscriptionHandler) Get(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	sub, err := h.repo.FindSubscriptionByID(r.Context(), id)
	if err != nil {
		if err == subscription.ErrNotFound {
			WriteNotFound(w, "Subscription not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get subscription")
		WriteInternalError(w, "Failed to get subscription")
		return
	}

	// Check access
	p := GetPrincipal(r.Context())
	if p != nil && !p.IsAnchor() && sub.ClientID != p.ClientID {
		WriteNotFound(w, "Subscription not found")
		return
	}

	WriteJSON(w, http.StatusOK, sub)
}

// Create handles POST /api/subscriptions
func (h *SubscriptionHandler) Create(w http.ResponseWriter, r *http.Request) {
	var req CreateSubscriptionRequest
	if err := DecodeJSON(r, &req); err != nil {
		WriteBadRequest(w, "Invalid request body")
		return
	}

	if req.Code == "" {
		WriteBadRequest(w, "Code is required")
		return
	}
	if req.Name == "" {
		WriteBadRequest(w, "Name is required")
		return
	}
	if req.Target == "" {
		WriteBadRequest(w, "Target is required")
		return
	}
	if len(req.EventTypes) == 0 {
		WriteBadRequest(w, "At least one event type is required")
		return
	}

	// Get client ID from authenticated principal
	p := GetPrincipal(r.Context())
	clientID := ""
	if p != nil {
		clientID = p.ClientID
	}

	sub := &subscription.Subscription{
		Code:             req.Code,
		Name:             req.Name,
		Description:      req.Description,
		ClientID:         clientID,
		EventTypes:       req.EventTypes,
		Target:           req.Target,
		Queue:            req.Queue,
		CustomConfig:     req.CustomConfig,
		Source:           subscription.SubscriptionSourceAPI,
		Status:           subscription.SubscriptionStatusActive,
		DispatchPoolCode: req.DispatchPoolCode,
		DelaySeconds:     req.DelaySeconds,
		Mode:             req.Mode,
		TimeoutSeconds:   req.TimeoutSeconds,
		DataOnly:         req.DataOnly,
	}

	if sub.Mode == "" {
		sub.Mode = subscription.DispatchModeImmediate
	}
	if sub.TimeoutSeconds == 0 {
		sub.TimeoutSeconds = 30
	}

	if err := h.repo.InsertSubscription(r.Context(), sub); err != nil {
		if err == subscription.ErrDuplicateCode {
			WriteConflict(w, "Subscription code already exists")
			return
		}
		log.Error().Err(err).Msg("Failed to create subscription")
		WriteInternalError(w, "Failed to create subscription")
		return
	}

	WriteJSON(w, http.StatusCreated, sub)
}

// Update handles PUT /api/subscriptions/{id}
func (h *SubscriptionHandler) Update(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	var req UpdateSubscriptionRequest
	if err := DecodeJSON(r, &req); err != nil {
		WriteBadRequest(w, "Invalid request body")
		return
	}

	sub, err := h.repo.FindSubscriptionByID(r.Context(), id)
	if err != nil {
		if err == subscription.ErrNotFound {
			WriteNotFound(w, "Subscription not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get subscription")
		WriteInternalError(w, "Failed to get subscription")
		return
	}

	// Check access
	p := GetPrincipal(r.Context())
	if p != nil && !p.IsAnchor() && sub.ClientID != p.ClientID {
		WriteNotFound(w, "Subscription not found")
		return
	}

	if req.Name != "" {
		sub.Name = req.Name
	}
	if req.Description != "" {
		sub.Description = req.Description
	}
	if req.Target != "" {
		sub.Target = req.Target
	}
	if req.CustomConfig != nil {
		sub.CustomConfig = req.CustomConfig
	}
	if req.DelaySeconds > 0 {
		sub.DelaySeconds = req.DelaySeconds
	}
	if req.TimeoutSeconds > 0 {
		sub.TimeoutSeconds = req.TimeoutSeconds
	}

	if err := h.repo.UpdateSubscription(r.Context(), sub); err != nil {
		log.Error().Err(err).Str("id", id).Msg("Failed to update subscription")
		WriteInternalError(w, "Failed to update subscription")
		return
	}

	WriteJSON(w, http.StatusOK, sub)
}

// Delete handles DELETE /api/subscriptions/{id}
func (h *SubscriptionHandler) Delete(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	// Check access first
	sub, err := h.repo.FindSubscriptionByID(r.Context(), id)
	if err != nil {
		if err == subscription.ErrNotFound {
			WriteNotFound(w, "Subscription not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get subscription")
		WriteInternalError(w, "Failed to get subscription")
		return
	}

	p := GetPrincipal(r.Context())
	if p != nil && !p.IsAnchor() && sub.ClientID != p.ClientID {
		WriteNotFound(w, "Subscription not found")
		return
	}

	if err := h.repo.DeleteSubscription(r.Context(), id); err != nil {
		log.Error().Err(err).Str("id", id).Msg("Failed to delete subscription")
		WriteInternalError(w, "Failed to delete subscription")
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

// Pause handles POST /api/subscriptions/{id}/pause
func (h *SubscriptionHandler) Pause(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	// Check access first
	sub, err := h.repo.FindSubscriptionByID(r.Context(), id)
	if err != nil {
		if err == subscription.ErrNotFound {
			WriteNotFound(w, "Subscription not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get subscription")
		WriteInternalError(w, "Failed to get subscription")
		return
	}

	p := GetPrincipal(r.Context())
	if p != nil && !p.IsAnchor() && sub.ClientID != p.ClientID {
		WriteNotFound(w, "Subscription not found")
		return
	}

	if err := h.repo.UpdateSubscriptionStatus(r.Context(), id, subscription.SubscriptionStatusPaused); err != nil {
		log.Error().Err(err).Str("id", id).Msg("Failed to pause subscription")
		WriteInternalError(w, "Failed to pause subscription")
		return
	}

	sub.Status = subscription.SubscriptionStatusPaused
	WriteJSON(w, http.StatusOK, sub)
}

// Resume handles POST /api/subscriptions/{id}/resume
func (h *SubscriptionHandler) Resume(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	// Check access first
	sub, err := h.repo.FindSubscriptionByID(r.Context(), id)
	if err != nil {
		if err == subscription.ErrNotFound {
			WriteNotFound(w, "Subscription not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get subscription")
		WriteInternalError(w, "Failed to get subscription")
		return
	}

	p := GetPrincipal(r.Context())
	if p != nil && !p.IsAnchor() && sub.ClientID != p.ClientID {
		WriteNotFound(w, "Subscription not found")
		return
	}

	if err := h.repo.UpdateSubscriptionStatus(r.Context(), id, subscription.SubscriptionStatusActive); err != nil {
		log.Error().Err(err).Str("id", id).Msg("Failed to resume subscription")
		WriteInternalError(w, "Failed to resume subscription")
		return
	}

	sub.Status = subscription.SubscriptionStatusActive
	WriteJSON(w, http.StatusOK, sub)
}

// === Dispatch Pool Handler ===

// DispatchPoolHandler handles dispatch pool endpoints
type DispatchPoolHandler struct {
	repo *subscription.Repository
}

// NewDispatchPoolHandler creates a new dispatch pool handler
func NewDispatchPoolHandler(repo *subscription.Repository) *DispatchPoolHandler {
	return &DispatchPoolHandler{repo: repo}
}

// Routes returns the router for dispatch pool endpoints
func (h *DispatchPoolHandler) Routes() chi.Router {
	r := chi.NewRouter()

	r.Get("/", h.List)
	r.Post("/", h.Create)
	r.Get("/{id}", h.Get)
	r.Put("/{id}", h.Update)
	r.Delete("/{id}", h.Delete)

	return r
}

// CreateDispatchPoolRequest represents a request to create a dispatch pool
type CreateDispatchPoolRequest struct {
	Code        string `json:"code"`
	Name        string `json:"name"`
	Description string `json:"description,omitempty"`
	RateLimit   *int   `json:"rateLimit,omitempty"`
	Concurrency int    `json:"concurrency"`
}

// UpdateDispatchPoolRequest represents a request to update a dispatch pool
type UpdateDispatchPoolRequest struct {
	Name        string `json:"name,omitempty"`
	Description string `json:"description,omitempty"`
	RateLimit   *int   `json:"rateLimit,omitempty"`
	Concurrency int    `json:"concurrency,omitempty"`
}

// List handles GET /api/dispatch-pools
func (h *DispatchPoolHandler) List(w http.ResponseWriter, r *http.Request) {
	p := GetPrincipal(r.Context())

	var pools []*subscription.DispatchPool
	var err error

	if p != nil && !p.IsAnchor() {
		pools, err = h.repo.FindDispatchPoolsByClient(r.Context(), p.ClientID)
	} else {
		pools, err = h.repo.FindAllDispatchPools(r.Context())
	}

	if err != nil {
		log.Error().Err(err).Msg("Failed to list dispatch pools")
		WriteInternalError(w, "Failed to list dispatch pools")
		return
	}

	WriteJSON(w, http.StatusOK, pools)
}

// Get handles GET /api/dispatch-pools/{id}
func (h *DispatchPoolHandler) Get(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	pool, err := h.repo.FindDispatchPoolByID(r.Context(), id)
	if err != nil {
		if err == subscription.ErrNotFound {
			WriteNotFound(w, "Dispatch pool not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get dispatch pool")
		WriteInternalError(w, "Failed to get dispatch pool")
		return
	}

	p := GetPrincipal(r.Context())
	if p != nil && !p.IsAnchor() && pool.ClientID != p.ClientID {
		WriteNotFound(w, "Dispatch pool not found")
		return
	}

	WriteJSON(w, http.StatusOK, pool)
}

// Create handles POST /api/dispatch-pools
func (h *DispatchPoolHandler) Create(w http.ResponseWriter, r *http.Request) {
	var req CreateDispatchPoolRequest
	if err := DecodeJSON(r, &req); err != nil {
		WriteBadRequest(w, "Invalid request body")
		return
	}

	if req.Code == "" {
		WriteBadRequest(w, "Code is required")
		return
	}
	if req.Name == "" {
		WriteBadRequest(w, "Name is required")
		return
	}
	if req.Concurrency <= 0 {
		req.Concurrency = 10
	}

	p := GetPrincipal(r.Context())
	clientID := ""
	if p != nil {
		clientID = p.ClientID
	}

	pool := &subscription.DispatchPool{
		Code:        req.Code,
		Name:        req.Name,
		Description: req.Description,
		RateLimit:   req.RateLimit,
		Concurrency: req.Concurrency,
		ClientID:    clientID,
		Status:      subscription.DispatchPoolStatusActive,
	}

	if err := h.repo.InsertDispatchPool(r.Context(), pool); err != nil {
		if err == subscription.ErrDuplicateCode {
			WriteConflict(w, "Dispatch pool code already exists")
			return
		}
		log.Error().Err(err).Msg("Failed to create dispatch pool")
		WriteInternalError(w, "Failed to create dispatch pool")
		return
	}

	WriteJSON(w, http.StatusCreated, pool)
}

// Update handles PUT /api/dispatch-pools/{id}
func (h *DispatchPoolHandler) Update(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	var req UpdateDispatchPoolRequest
	if err := DecodeJSON(r, &req); err != nil {
		WriteBadRequest(w, "Invalid request body")
		return
	}

	pool, err := h.repo.FindDispatchPoolByID(r.Context(), id)
	if err != nil {
		if err == subscription.ErrNotFound {
			WriteNotFound(w, "Dispatch pool not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get dispatch pool")
		WriteInternalError(w, "Failed to get dispatch pool")
		return
	}

	p := GetPrincipal(r.Context())
	if p != nil && !p.IsAnchor() && pool.ClientID != p.ClientID {
		WriteNotFound(w, "Dispatch pool not found")
		return
	}

	if req.Name != "" {
		pool.Name = req.Name
	}
	if req.Description != "" {
		pool.Description = req.Description
	}
	if req.RateLimit != nil {
		pool.RateLimit = req.RateLimit
	}
	if req.Concurrency > 0 {
		pool.Concurrency = req.Concurrency
	}

	if err := h.repo.UpdateDispatchPool(r.Context(), pool); err != nil {
		log.Error().Err(err).Str("id", id).Msg("Failed to update dispatch pool")
		WriteInternalError(w, "Failed to update dispatch pool")
		return
	}

	WriteJSON(w, http.StatusOK, pool)
}

// Delete handles DELETE /api/dispatch-pools/{id}
func (h *DispatchPoolHandler) Delete(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	pool, err := h.repo.FindDispatchPoolByID(r.Context(), id)
	if err != nil {
		if err == subscription.ErrNotFound {
			WriteNotFound(w, "Dispatch pool not found")
			return
		}
		log.Error().Err(err).Str("id", id).Msg("Failed to get dispatch pool")
		WriteInternalError(w, "Failed to get dispatch pool")
		return
	}

	p := GetPrincipal(r.Context())
	if p != nil && !p.IsAnchor() && pool.ClientID != p.ClientID {
		WriteNotFound(w, "Dispatch pool not found")
		return
	}

	if err := h.repo.DeleteDispatchPool(r.Context(), id); err != nil {
		log.Error().Err(err).Str("id", id).Msg("Failed to delete dispatch pool")
		WriteInternalError(w, "Failed to delete dispatch pool")
		return
	}

	w.WriteHeader(http.StatusNoContent)
}
