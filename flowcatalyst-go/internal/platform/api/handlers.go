package api

import (
	"net/http"

	"go.mongodb.org/mongo-driver/mongo"

	"go.flowcatalyst.tech/internal/config"
	"go.flowcatalyst.tech/internal/platform/application"
	"go.flowcatalyst.tech/internal/platform/audit"
	"go.flowcatalyst.tech/internal/platform/client"
	"go.flowcatalyst.tech/internal/platform/dispatchjob"
	"go.flowcatalyst.tech/internal/platform/dispatchpool"
	"go.flowcatalyst.tech/internal/platform/event"
	"go.flowcatalyst.tech/internal/platform/eventtype"
	"go.flowcatalyst.tech/internal/platform/permission"
	"go.flowcatalyst.tech/internal/platform/principal"
	"go.flowcatalyst.tech/internal/platform/role"
	"go.flowcatalyst.tech/internal/platform/serviceaccount"
	"go.flowcatalyst.tech/internal/platform/subscription"
)

// Handlers contains all API handlers
type Handlers struct {
	db     *mongo.Database
	config *config.Config

	// Repositories
	eventRepo          *event.Repository
	eventTypeRepo      *eventtype.Repository
	subscriptionRepo   *subscription.Repository
	dispatchPoolRepo   *dispatchpool.Repository
	dispatchJobRepo    *dispatchjob.Repository
	clientRepo         *client.Repository
	principalRepo      *principal.Repository
	roleRepo           *role.Repository
	permissionRepo     *permission.Repository
	applicationRepo    *application.Repository
	serviceAccountRepo *serviceaccount.Repository
	auditRepo          *audit.Repository

	// Services
	auditService *audit.Service

	// Individual handlers
	eventHandler        *EventHandler
	subscriptionHandler *SubscriptionHandler
	clientHandler       *ClientAdminHandler
	principalHandler    *PrincipalAdminHandler
	bffEventHandler     *EventBffHandler
	bffDispatchHandler  *DispatchJobBffHandler
	dispatchJobHandler  *DispatchJobHandler
	auditLogHandler     *AuditLogHandler
	authConfigHandler   *AuthConfigHandler
	anchorDomainHandler *AnchorDomainHandler
}

// NewHandlers creates all API handlers
func NewHandlers(db *mongo.Database, cfg *config.Config) *Handlers {
	h := &Handlers{
		db:     db,
		config: cfg,
	}

	// Initialize repositories
	h.eventRepo = event.NewRepository(db)
	h.eventTypeRepo = eventtype.NewRepository(db)
	h.subscriptionRepo = subscription.NewRepository(db)
	h.dispatchPoolRepo = dispatchpool.NewRepository(db)
	h.dispatchJobRepo = dispatchjob.NewRepository(db)
	h.clientRepo = client.NewRepository(db)
	h.principalRepo = principal.NewRepository(db)
	h.roleRepo = role.NewRepository(db)
	h.permissionRepo = permission.NewRepository(db)
	h.applicationRepo = application.NewRepository(db)
	h.serviceAccountRepo = serviceaccount.NewRepository(db)
	h.auditRepo = audit.NewRepository(db)

	// Initialize services
	h.auditService = audit.NewService(h.auditRepo)

	// Initialize handlers
	h.eventHandler = NewEventHandler(h.eventRepo)
	h.subscriptionHandler = NewSubscriptionHandler(h.subscriptionRepo)
	h.clientHandler = NewClientAdminHandler(h.clientRepo)
	h.principalHandler = NewPrincipalAdminHandler(h.principalRepo, h.clientRepo)
	h.bffEventHandler = NewEventBffHandler(db)
	h.bffDispatchHandler = NewDispatchJobBffHandler(db)
	h.dispatchJobHandler = NewDispatchJobHandler(h.dispatchJobRepo)
	h.auditLogHandler = NewAuditLogHandler(h.auditRepo, h.principalRepo)
	h.authConfigHandler = NewAuthConfigHandler(h.clientRepo)
	h.anchorDomainHandler = NewAnchorDomainHandler(h.clientRepo)

	return h
}

// Event handlers

func (h *Handlers) CreateEvent(w http.ResponseWriter, r *http.Request) {
	h.eventHandler.Create(w, r)
}

func (h *Handlers) CreateEventBatch(w http.ResponseWriter, r *http.Request) {
	h.eventHandler.CreateBatch(w, r)
}

func (h *Handlers) GetEvent(w http.ResponseWriter, r *http.Request) {
	h.eventHandler.Get(w, r)
}

// Event Type handlers

func (h *Handlers) ListEventTypes(w http.ResponseWriter, r *http.Request) {
	h.eventTypeRepo.ListHandler(w, r)
}

func (h *Handlers) CreateEventType(w http.ResponseWriter, r *http.Request) {
	h.eventTypeRepo.CreateHandler(w, r)
}

func (h *Handlers) GetEventType(w http.ResponseWriter, r *http.Request) {
	h.eventTypeRepo.GetHandler(w, r)
}

func (h *Handlers) UpdateEventType(w http.ResponseWriter, r *http.Request) {
	h.eventTypeRepo.UpdateHandler(w, r)
}

func (h *Handlers) DeleteEventType(w http.ResponseWriter, r *http.Request) {
	h.eventTypeRepo.DeleteHandler(w, r)
}

// Subscription handlers

func (h *Handlers) ListSubscriptions(w http.ResponseWriter, r *http.Request) {
	h.subscriptionHandler.List(w, r)
}

func (h *Handlers) CreateSubscription(w http.ResponseWriter, r *http.Request) {
	h.subscriptionHandler.Create(w, r)
}

func (h *Handlers) GetSubscription(w http.ResponseWriter, r *http.Request) {
	h.subscriptionHandler.Get(w, r)
}

func (h *Handlers) UpdateSubscription(w http.ResponseWriter, r *http.Request) {
	h.subscriptionHandler.Update(w, r)
}

func (h *Handlers) DeleteSubscription(w http.ResponseWriter, r *http.Request) {
	h.subscriptionHandler.Delete(w, r)
}

func (h *Handlers) PauseSubscription(w http.ResponseWriter, r *http.Request) {
	h.subscriptionHandler.Pause(w, r)
}

func (h *Handlers) ResumeSubscription(w http.ResponseWriter, r *http.Request) {
	h.subscriptionHandler.Resume(w, r)
}

// Dispatch Pool handlers

func (h *Handlers) ListDispatchPools(w http.ResponseWriter, r *http.Request) {
	h.dispatchPoolRepo.ListHandler(w, r)
}

func (h *Handlers) CreateDispatchPool(w http.ResponseWriter, r *http.Request) {
	h.dispatchPoolRepo.CreateHandler(w, r)
}

func (h *Handlers) GetDispatchPool(w http.ResponseWriter, r *http.Request) {
	h.dispatchPoolRepo.GetHandler(w, r)
}

func (h *Handlers) UpdateDispatchPool(w http.ResponseWriter, r *http.Request) {
	h.dispatchPoolRepo.UpdateHandler(w, r)
}

func (h *Handlers) DeleteDispatchPool(w http.ResponseWriter, r *http.Request) {
	h.dispatchPoolRepo.DeleteHandler(w, r)
}

// Dispatch Job handlers

func (h *Handlers) CreateDispatchJob(w http.ResponseWriter, r *http.Request) {
	h.dispatchJobHandler.Create(w, r)
}

func (h *Handlers) CreateDispatchJobBatch(w http.ResponseWriter, r *http.Request) {
	h.dispatchJobHandler.CreateBatch(w, r)
}

func (h *Handlers) SearchDispatchJobs(w http.ResponseWriter, r *http.Request) {
	h.dispatchJobHandler.Search(w, r)
}

func (h *Handlers) GetDispatchJob(w http.ResponseWriter, r *http.Request) {
	h.dispatchJobHandler.Get(w, r)
}

func (h *Handlers) GetDispatchJobAttempts(w http.ResponseWriter, r *http.Request) {
	h.dispatchJobHandler.GetAttempts(w, r)
}

// BFF handlers

func (h *Handlers) BFFSearchEvents(w http.ResponseWriter, r *http.Request) {
	h.bffEventHandler.Search(w, r)
}

func (h *Handlers) BFFEventFilterOptions(w http.ResponseWriter, r *http.Request) {
	h.bffEventHandler.FilterOptions(w, r)
}

func (h *Handlers) BFFGetEvent(w http.ResponseWriter, r *http.Request) {
	h.bffEventHandler.Get(w, r)
}

func (h *Handlers) BFFSearchDispatchJobs(w http.ResponseWriter, r *http.Request) {
	h.bffDispatchHandler.Search(w, r)
}

func (h *Handlers) BFFDispatchJobFilterOptions(w http.ResponseWriter, r *http.Request) {
	h.bffDispatchHandler.FilterOptions(w, r)
}

func (h *Handlers) BFFGetDispatchJob(w http.ResponseWriter, r *http.Request) {
	h.bffDispatchHandler.Get(w, r)
}

// Client handlers

func (h *Handlers) ListClients(w http.ResponseWriter, r *http.Request) {
	h.clientHandler.List(w, r)
}

func (h *Handlers) CreateClient(w http.ResponseWriter, r *http.Request) {
	h.clientHandler.Create(w, r)
}

func (h *Handlers) GetClient(w http.ResponseWriter, r *http.Request) {
	h.clientHandler.Get(w, r)
}

func (h *Handlers) UpdateClient(w http.ResponseWriter, r *http.Request) {
	h.clientHandler.Update(w, r)
}

func (h *Handlers) SuspendClient(w http.ResponseWriter, r *http.Request) {
	h.clientHandler.Suspend(w, r)
}

func (h *Handlers) ActivateClient(w http.ResponseWriter, r *http.Request) {
	h.clientHandler.Activate(w, r)
}

// Principal handlers

func (h *Handlers) ListPrincipals(w http.ResponseWriter, r *http.Request) {
	h.principalHandler.List(w, r)
}

func (h *Handlers) CreatePrincipal(w http.ResponseWriter, r *http.Request) {
	h.principalHandler.Create(w, r)
}

func (h *Handlers) GetPrincipal(w http.ResponseWriter, r *http.Request) {
	h.principalHandler.Get(w, r)
}

func (h *Handlers) UpdatePrincipal(w http.ResponseWriter, r *http.Request) {
	h.principalHandler.Update(w, r)
}

func (h *Handlers) ActivatePrincipal(w http.ResponseWriter, r *http.Request) {
	h.principalHandler.Activate(w, r)
}

func (h *Handlers) DeactivatePrincipal(w http.ResponseWriter, r *http.Request) {
	h.principalHandler.Deactivate(w, r)
}

// Role handlers

func (h *Handlers) ListRoles(w http.ResponseWriter, r *http.Request) {
	h.roleRepo.ListHandler(w, r)
}

func (h *Handlers) CreateRole(w http.ResponseWriter, r *http.Request) {
	h.roleRepo.CreateHandler(w, r)
}

func (h *Handlers) GetRole(w http.ResponseWriter, r *http.Request) {
	h.roleRepo.GetHandler(w, r)
}

func (h *Handlers) UpdateRole(w http.ResponseWriter, r *http.Request) {
	h.roleRepo.UpdateHandler(w, r)
}

func (h *Handlers) DeleteRole(w http.ResponseWriter, r *http.Request) {
	h.roleRepo.DeleteHandler(w, r)
}

// Permission handlers

func (h *Handlers) ListPermissions(w http.ResponseWriter, r *http.Request) {
	h.permissionRepo.ListHandler(w, r)
}

// Application handlers

func (h *Handlers) ListApplications(w http.ResponseWriter, r *http.Request) {
	h.applicationRepo.ListHandler(w, r)
}

func (h *Handlers) CreateApplication(w http.ResponseWriter, r *http.Request) {
	h.applicationRepo.CreateHandler(w, r)
}

func (h *Handlers) GetApplication(w http.ResponseWriter, r *http.Request) {
	h.applicationRepo.GetHandler(w, r)
}

func (h *Handlers) UpdateApplication(w http.ResponseWriter, r *http.Request) {
	h.applicationRepo.UpdateHandler(w, r)
}

func (h *Handlers) DeleteApplication(w http.ResponseWriter, r *http.Request) {
	h.applicationRepo.DeleteHandler(w, r)
}

// Service Account handlers

func (h *Handlers) ListServiceAccounts(w http.ResponseWriter, r *http.Request) {
	h.serviceAccountRepo.ListHandler(w, r)
}

func (h *Handlers) CreateServiceAccount(w http.ResponseWriter, r *http.Request) {
	h.serviceAccountRepo.CreateHandler(w, r)
}

func (h *Handlers) GetServiceAccount(w http.ResponseWriter, r *http.Request) {
	h.serviceAccountRepo.GetHandler(w, r)
}

func (h *Handlers) UpdateServiceAccount(w http.ResponseWriter, r *http.Request) {
	h.serviceAccountRepo.UpdateHandler(w, r)
}

func (h *Handlers) DeleteServiceAccount(w http.ResponseWriter, r *http.Request) {
	h.serviceAccountRepo.DeleteHandler(w, r)
}

func (h *Handlers) RegenerateServiceAccountCredentials(w http.ResponseWriter, r *http.Request) {
	h.serviceAccountRepo.RegenerateHandler(w, r)
}

// OAuth Client handlers (placeholder for now)

func (h *Handlers) ListOAuthClients(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusOK, []interface{}{})
}

func (h *Handlers) CreateOAuthClient(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusCreated, map[string]string{"id": "placeholder"})
}

func (h *Handlers) GetOAuthClient(w http.ResponseWriter, r *http.Request) {
	WriteNotFound(w, "OAuth client not found")
}

func (h *Handlers) UpdateOAuthClient(w http.ResponseWriter, r *http.Request) {
	WriteNotFound(w, "OAuth client not found")
}

func (h *Handlers) DeleteOAuthClient(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusNoContent)
}

// Auth handlers (placeholder - will be expanded)

func (h *Handlers) Login(w http.ResponseWriter, r *http.Request) {
	// Placeholder - will be implemented with auth package
	WriteBadRequest(w, "Auth not yet implemented")
}

func (h *Handlers) Logout(w http.ResponseWriter, r *http.Request) {
	// Clear session cookie
	http.SetCookie(w, &http.Cookie{
		Name:     h.config.Auth.Session.CookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   h.config.Auth.Session.Secure,
	})
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) GetCurrentUser(w http.ResponseWriter, r *http.Request) {
	p := GetPrincipal(r.Context())
	if p == nil {
		WriteUnauthorized(w, "Not authenticated")
		return
	}
	WriteJSON(w, http.StatusOK, p)
}

func (h *Handlers) CheckDomain(w http.ResponseWriter, r *http.Request) {
	// Placeholder
	WriteJSON(w, http.StatusOK, map[string]interface{}{
		"authMethod": "LOCAL",
	})
}

// OAuth/OIDC handlers (placeholder - will be expanded with Fosite)

func (h *Handlers) OAuthAuthorize(w http.ResponseWriter, r *http.Request) {
	WriteBadRequest(w, "OAuth not yet implemented")
}

func (h *Handlers) OAuthToken(w http.ResponseWriter, r *http.Request) {
	WriteBadRequest(w, "OAuth not yet implemented")
}

func (h *Handlers) OIDCDiscovery(w http.ResponseWriter, r *http.Request) {
	baseURL := h.config.Auth.ExternalBase
	if baseURL == "" {
		baseURL = "http://localhost:8080"
	}

	discovery := map[string]interface{}{
		"issuer":                 h.config.Auth.JWT.Issuer,
		"authorization_endpoint": baseURL + "/oauth/authorize",
		"token_endpoint":         baseURL + "/oauth/token",
		"jwks_uri":               baseURL + "/.well-known/jwks.json",
		"response_types_supported": []string{
			"code",
			"token",
			"id_token",
			"code token",
			"code id_token",
			"token id_token",
			"code token id_token",
		},
		"subject_types_supported":               []string{"public"},
		"id_token_signing_alg_values_supported": []string{"RS256"},
		"scopes_supported":                      []string{"openid", "profile", "email"},
		"token_endpoint_auth_methods_supported": []string{"client_secret_basic", "client_secret_post"},
		"claims_supported": []string{
			"sub", "iss", "aud", "exp", "iat", "name", "email",
		},
		"code_challenge_methods_supported": []string{"S256"},
	}

	WriteJSON(w, http.StatusOK, discovery)
}

func (h *Handlers) JWKS(w http.ResponseWriter, r *http.Request) {
	// Placeholder - will return actual public keys
	jwks := map[string]interface{}{
		"keys": []interface{}{},
	}
	WriteJSON(w, http.StatusOK, jwks)
}

// Audit log handlers

func (h *Handlers) ListAuditLogs(w http.ResponseWriter, r *http.Request) {
	h.auditLogHandler.List(w, r)
}

func (h *Handlers) GetAuditLog(w http.ResponseWriter, r *http.Request) {
	h.auditLogHandler.Get(w, r)
}

func (h *Handlers) GetAuditLogsForEntity(w http.ResponseWriter, r *http.Request) {
	h.auditLogHandler.GetForEntity(w, r)
}

func (h *Handlers) GetAuditEntityTypes(w http.ResponseWriter, r *http.Request) {
	h.auditLogHandler.GetEntityTypes(w, r)
}

func (h *Handlers) GetAuditOperations(w http.ResponseWriter, r *http.Request) {
	h.auditLogHandler.GetOperations(w, r)
}

// GetAuditService returns the audit service for use in other handlers
func (h *Handlers) GetAuditService() *audit.Service {
	return h.auditService
}

// Auth config handlers

func (h *Handlers) ListAuthConfigs(w http.ResponseWriter, r *http.Request) {
	h.authConfigHandler.List(w, r)
}

func (h *Handlers) CreateAuthConfig(w http.ResponseWriter, r *http.Request) {
	h.authConfigHandler.Create(w, r)
}

func (h *Handlers) GetAuthConfig(w http.ResponseWriter, r *http.Request) {
	h.authConfigHandler.Get(w, r)
}

func (h *Handlers) UpdateAuthConfig(w http.ResponseWriter, r *http.Request) {
	h.authConfigHandler.Update(w, r)
}

func (h *Handlers) DeleteAuthConfig(w http.ResponseWriter, r *http.Request) {
	h.authConfigHandler.Delete(w, r)
}

// Anchor domain handlers

func (h *Handlers) ListAnchorDomains(w http.ResponseWriter, r *http.Request) {
	h.anchorDomainHandler.List(w, r)
}

func (h *Handlers) CreateAnchorDomain(w http.ResponseWriter, r *http.Request) {
	h.anchorDomainHandler.Create(w, r)
}

func (h *Handlers) DeleteAnchorDomain(w http.ResponseWriter, r *http.Request) {
	h.anchorDomainHandler.Delete(w, r)
}
