// FlowCatalyst Platform API
//
// Standalone platform API binary for production deployments.
// Provides control plane APIs, authentication, and admin functionality.
//
//	@title			FlowCatalyst Platform API
//	@version		1.0
//	@description	Control plane API for FlowCatalyst - multi-tenant event routing platform.
//
//	@contact.name	FlowCatalyst Support
//	@contact.url	https://flowcatalyst.tech/support
//	@contact.email	support@flowcatalyst.tech
//
//	@license.name	Proprietary
//	@license.url	https://flowcatalyst.tech/license
//
//	@host		localhost:8080
//	@BasePath	/api
//
//	@securityDefinitions.apikey	BearerAuth
//	@in							header
//	@name						Authorization
//	@description				JWT Bearer token. Format: "Bearer {token}"
//
//	@securityDefinitions.apikey	SessionCookie
//	@in							cookie
//	@name						FLOWCATALYST_SESSION
//	@description				Session cookie for browser-based authentication

package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/go-chi/cors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
	httpSwagger "github.com/swaggo/http-swagger"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	_ "go.flowcatalyst.tech/docs" // Swagger docs

	"go.flowcatalyst.tech/internal/common/health"
	"go.flowcatalyst.tech/internal/config"
	"go.flowcatalyst.tech/internal/platform/api"
	"go.flowcatalyst.tech/internal/platform/auth"
	"go.flowcatalyst.tech/internal/platform/auth/federation"
	"go.flowcatalyst.tech/internal/platform/auth/jwt"
	"go.flowcatalyst.tech/internal/platform/auth/oidc"
	"go.flowcatalyst.tech/internal/platform/auth/session"
	"go.flowcatalyst.tech/internal/platform/client"
	"go.flowcatalyst.tech/internal/platform/principal"
)

var (
	version   = "dev"
	buildTime = "unknown"
)

func main() {
	// Configure logging
	zerolog.TimeFieldFormat = time.RFC3339
	if os.Getenv("FLOWCATALYST_DEV") == "true" {
		log.Logger = log.Output(zerolog.ConsoleWriter{Out: os.Stderr, TimeFormat: time.RFC3339})
	}

	log.Info().
		Str("version", version).
		Str("build_time", buildTime).
		Str("component", "platform").
		Msg("Starting FlowCatalyst Platform API")

	// Load configuration
	cfg, err := config.Load()
	if err != nil {
		log.Fatal().Err(err).Msg("Failed to load configuration")
	}

	// Create context for graceful shutdown
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Initialize health checker
	healthChecker := health.NewChecker()

	// Initialize MongoDB connection
	log.Info().Str("uri", maskURI(cfg.MongoDB.URI)).Msg("Connecting to MongoDB")
	mongoClient, err := mongo.Connect(ctx, options.Client().ApplyURI(cfg.MongoDB.URI))
	if err != nil {
		log.Fatal().Err(err).Msg("Failed to connect to MongoDB")
	}
	defer func() {
		if err := mongoClient.Disconnect(ctx); err != nil {
			log.Error().Err(err).Msg("Error disconnecting from MongoDB")
		}
	}()

	// Ping MongoDB to verify connection
	if err := mongoClient.Ping(ctx, nil); err != nil {
		log.Fatal().Err(err).Msg("Failed to ping MongoDB")
	}
	log.Info().Str("database", cfg.MongoDB.Database).Msg("Connected to MongoDB")

	// Add MongoDB health check
	healthChecker.AddReadinessCheck(health.MongoDBCheck(func() error {
		return mongoClient.Ping(ctx, nil)
	}))

	// Initialize database reference
	db := mongoClient.Database(cfg.MongoDB.Database)

	// Initialize API handlers
	apiHandlers := api.NewHandlers(db, cfg)

	// Initialize Auth Service
	keyManager := jwt.NewKeyManager()
	devKeyDir := cfg.DataDir
	if devKeyDir == "" {
		devKeyDir = "./data"
	}
	if err := keyManager.Initialize("", "", devKeyDir+"/keys"); err != nil {
		log.Fatal().Err(err).Msg("Failed to initialize key manager")
	}

	tokenService := jwt.NewTokenService(keyManager, jwt.TokenServiceConfig{
		Issuer:             cfg.Auth.JWT.Issuer,
		AccessTokenExpiry:  cfg.Auth.JWT.AccessTokenExpiry,
		SessionTokenExpiry: cfg.Auth.JWT.SessionTokenExpiry,
		RefreshTokenExpiry: cfg.Auth.JWT.RefreshTokenExpiry,
		AuthCodeExpiry:     cfg.Auth.JWT.AuthorizationCodeExpiry,
	})

	sessionManager := session.NewManager(session.Config{
		CookieName: cfg.Auth.Session.CookieName,
		Path:       "/",
		Domain:     "",
		MaxAge:     cfg.Auth.JWT.SessionTokenExpiry,
		Secure:     cfg.Auth.Session.Secure,
		SameSite:   http.SameSiteStrictMode,
	})

	federationService := federation.NewService()

	principalRepo := principal.NewRepository(db)
	clientRepo := client.NewRepository(db)
	oidcRepo := oidc.NewRepository(db)

	authService := auth.NewAuthService(
		principalRepo,
		clientRepo,
		oidcRepo,
		tokenService,
		sessionManager,
		federationService,
		cfg.Auth.ExternalBase,
	)

	// Create OIDC discovery handler
	discoveryHandler := oidc.NewDiscoveryHandler(keyManager, cfg.Auth.JWT.Issuer, cfg.Auth.ExternalBase)

	log.Info().Msg("Auth service initialized")

	// Set up HTTP router
	r := chi.NewRouter()

	// Middleware stack
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.Timeout(60 * time.Second))

	// CORS configuration
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   cfg.HTTP.CORSOrigins,
		AllowedMethods:   []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "X-Request-ID"},
		ExposedHeaders:   []string{"Link", "X-Request-ID"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	// Health endpoints
	r.Get("/q/health", healthChecker.HandleHealth)
	r.Get("/q/health/live", healthChecker.HandleLive)
	r.Get("/q/health/ready", healthChecker.HandleReady)

	// Swagger documentation
	r.Get("/swagger/*", httpSwagger.Handler(
		httpSwagger.URL("/swagger/doc.json"),
	))

	// Prometheus metrics
	r.Handle("/metrics", promhttp.Handler())
	r.Handle("/q/metrics", promhttp.Handler())

	// Mount API routes
	r.Route("/api", func(r chi.Router) {
		// Events API
		r.Route("/events", func(r chi.Router) {
			r.Post("/", apiHandlers.CreateEvent)
			r.Post("/batch", apiHandlers.CreateEventBatch)
			r.Get("/{id}", apiHandlers.GetEvent)
		})

		// Event Types API
		r.Route("/event-types", func(r chi.Router) {
			r.Get("/", apiHandlers.ListEventTypes)
			r.Post("/", apiHandlers.CreateEventType)
			r.Get("/{id}", apiHandlers.GetEventType)
			r.Put("/{id}", apiHandlers.UpdateEventType)
			r.Delete("/{id}", apiHandlers.DeleteEventType)
		})

		// Subscriptions API
		r.Route("/subscriptions", func(r chi.Router) {
			r.Get("/", apiHandlers.ListSubscriptions)
			r.Post("/", apiHandlers.CreateSubscription)
			r.Get("/{id}", apiHandlers.GetSubscription)
			r.Put("/{id}", apiHandlers.UpdateSubscription)
			r.Delete("/{id}", apiHandlers.DeleteSubscription)
			r.Post("/{id}/pause", apiHandlers.PauseSubscription)
			r.Post("/{id}/resume", apiHandlers.ResumeSubscription)
		})

		// Dispatch Pools API
		r.Route("/dispatch-pools", func(r chi.Router) {
			r.Get("/", apiHandlers.ListDispatchPools)
			r.Post("/", apiHandlers.CreateDispatchPool)
			r.Get("/{id}", apiHandlers.GetDispatchPool)
			r.Put("/{id}", apiHandlers.UpdateDispatchPool)
			r.Delete("/{id}", apiHandlers.DeleteDispatchPool)
		})

		// Dispatch Jobs API
		r.Route("/dispatch/jobs", func(r chi.Router) {
			r.Post("/", apiHandlers.CreateDispatchJob)
			r.Post("/batch", apiHandlers.CreateDispatchJobBatch)
			r.Get("/", apiHandlers.SearchDispatchJobs)
			r.Get("/{id}", apiHandlers.GetDispatchJob)
			r.Get("/{id}/attempts", apiHandlers.GetDispatchJobAttempts)
		})

		// BFF APIs (read projections)
		r.Route("/bff", func(r chi.Router) {
			r.Get("/events", apiHandlers.BFFSearchEvents)
			r.Get("/events/filter-options", apiHandlers.BFFEventFilterOptions)
			r.Get("/events/{id}", apiHandlers.BFFGetEvent)

			r.Get("/dispatch-jobs", apiHandlers.BFFSearchDispatchJobs)
			r.Get("/dispatch-jobs/filter-options", apiHandlers.BFFDispatchJobFilterOptions)
			r.Get("/dispatch-jobs/{id}", apiHandlers.BFFGetDispatchJob)
		})
	})

	// Admin API routes
	r.Route("/api/admin/platform", func(r chi.Router) {
		// Clients
		r.Route("/clients", func(r chi.Router) {
			r.Get("/", apiHandlers.ListClients)
			r.Post("/", apiHandlers.CreateClient)
			r.Get("/{id}", apiHandlers.GetClient)
			r.Put("/{id}", apiHandlers.UpdateClient)
			r.Post("/{id}/suspend", apiHandlers.SuspendClient)
			r.Post("/{id}/activate", apiHandlers.ActivateClient)
		})

		// Principals
		r.Route("/principals", func(r chi.Router) {
			r.Get("/", apiHandlers.ListPrincipals)
			r.Post("/", apiHandlers.CreatePrincipal)
			r.Get("/{id}", apiHandlers.GetPrincipal)
			r.Put("/{id}", apiHandlers.UpdatePrincipal)
			r.Post("/{id}/activate", apiHandlers.ActivatePrincipal)
			r.Post("/{id}/deactivate", apiHandlers.DeactivatePrincipal)
		})

		// Roles
		r.Route("/roles", func(r chi.Router) {
			r.Get("/", apiHandlers.ListRoles)
			r.Post("/", apiHandlers.CreateRole)
			r.Get("/{id}", apiHandlers.GetRole)
			r.Put("/{id}", apiHandlers.UpdateRole)
			r.Delete("/{id}", apiHandlers.DeleteRole)
		})

		// Permissions
		r.Route("/permissions", func(r chi.Router) {
			r.Get("/", apiHandlers.ListPermissions)
		})

		// Applications
		r.Route("/applications", func(r chi.Router) {
			r.Get("/", apiHandlers.ListApplications)
			r.Post("/", apiHandlers.CreateApplication)
			r.Get("/{id}", apiHandlers.GetApplication)
			r.Put("/{id}", apiHandlers.UpdateApplication)
			r.Delete("/{id}", apiHandlers.DeleteApplication)
		})

		// Service Accounts
		r.Route("/service-accounts", func(r chi.Router) {
			r.Get("/", apiHandlers.ListServiceAccounts)
			r.Post("/", apiHandlers.CreateServiceAccount)
			r.Get("/{id}", apiHandlers.GetServiceAccount)
			r.Put("/{id}", apiHandlers.UpdateServiceAccount)
			r.Delete("/{id}", apiHandlers.DeleteServiceAccount)
			r.Post("/{id}/regenerate", apiHandlers.RegenerateServiceAccountCredentials)
		})

		// OAuth Clients
		r.Route("/oauth-clients", func(r chi.Router) {
			r.Get("/", apiHandlers.ListOAuthClients)
			r.Post("/", apiHandlers.CreateOAuthClient)
			r.Get("/{id}", apiHandlers.GetOAuthClient)
			r.Put("/{id}", apiHandlers.UpdateOAuthClient)
			r.Delete("/{id}", apiHandlers.DeleteOAuthClient)
		})

		// Audit Logs
		r.Route("/audit-logs", func(r chi.Router) {
			r.Get("/", apiHandlers.ListAuditLogs)
			r.Get("/entity-types", apiHandlers.GetAuditEntityTypes)
			r.Get("/operations", apiHandlers.GetAuditOperations)
			r.Get("/entity/{entityType}/{entityId}", apiHandlers.GetAuditLogsForEntity)
			r.Get("/{id}", apiHandlers.GetAuditLog)
		})

		// Auth Configs
		r.Route("/auth-configs", func(r chi.Router) {
			r.Get("/", apiHandlers.ListAuthConfigs)
			r.Post("/", apiHandlers.CreateAuthConfig)
			r.Get("/{id}", apiHandlers.GetAuthConfig)
			r.Put("/{id}", apiHandlers.UpdateAuthConfig)
			r.Delete("/{id}", apiHandlers.DeleteAuthConfig)
		})

		// Anchor Domains
		r.Route("/anchor-domains", func(r chi.Router) {
			r.Get("/", apiHandlers.ListAnchorDomains)
			r.Post("/", apiHandlers.CreateAnchorDomain)
			r.Delete("/{domain}", apiHandlers.DeleteAnchorDomain)
		})
	})

	// Auth endpoints
	r.Route("/auth", func(r chi.Router) {
		r.Post("/login", authService.HandleLogin)
		r.Post("/logout", authService.HandleLogout)
		r.Get("/me", authService.HandleMe)
		r.Post("/check-domain", authService.HandleCheckDomain)

		// OIDC Federation endpoints
		r.Get("/oidc/login", authService.HandleOIDCLogin)
		r.Get("/oidc/callback", authService.HandleOIDCCallback)
	})

	// OAuth/OIDC endpoints
	r.Get("/oauth/authorize", authService.HandleAuthorize)
	r.Post("/oauth/token", authService.HandleToken)

	// OIDC discovery endpoints
	r.Get("/.well-known/openid-configuration", discoveryHandler.HandleDiscovery)
	r.Get("/.well-known/jwks.json", discoveryHandler.HandleJWKS)

	// Start HTTP server
	server := &http.Server{
		Addr:         fmt.Sprintf(":%d", cfg.HTTP.Port),
		Handler:      r,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		log.Info().Int("port", cfg.HTTP.Port).Msg("HTTP server starting")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal().Err(err).Msg("HTTP server failed")
		}
	}()

	// Wait for interrupt signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Info().Msg("Shutting down gracefully...")

	// Graceful shutdown
	shutdownCtx, shutdownCancel := context.WithTimeout(ctx, 30*time.Second)
	defer shutdownCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Error().Err(err).Msg("HTTP server forced to shutdown")
	}

	log.Info().Msg("FlowCatalyst Platform API stopped")
}

// maskURI masks sensitive parts of a MongoDB URI for logging
func maskURI(uri string) string {
	if len(uri) > 20 {
		return uri[:20] + "..."
	}
	return uri
}
