// FlowCatalyst Outbox Processor
//
// Standalone outbox processor binary for production deployments.
// Polls customer databases for pending outbox items and sends them to FlowCatalyst APIs.

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
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"go.flowcatalyst.tech/internal/common/health"
	"go.flowcatalyst.tech/internal/config"
	"go.flowcatalyst.tech/internal/outbox"
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
		Str("component", "outbox").
		Msg("Starting FlowCatalyst Outbox Processor")

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

	// Get outbox configuration from environment
	apiBaseURL := getEnv("OUTBOX_API_BASE_URL", "http://localhost:8080")
	apiAuthToken := getEnv("OUTBOX_API_AUTH_TOKEN", "")

	// Initialize outbox repository
	repoConfig := outbox.DefaultRepositoryConfig()
	repo := outbox.NewMongoRepository(db, repoConfig)

	// Initialize API client
	apiClientConfig := &outbox.APIClientConfig{
		BaseURL:           apiBaseURL,
		AuthToken:         apiAuthToken,
		ConnectionTimeout: 10 * time.Second,
		RequestTimeout:    30 * time.Second,
	}
	apiClient := outbox.NewAPIClient(apiClientConfig)

	// Initialize processor config
	processorConfig := outbox.DefaultProcessorConfig()
	processorConfig.Enabled = true

	// Override from environment
	if interval := getEnvDuration("OUTBOX_POLL_INTERVAL", 0); interval > 0 {
		processorConfig.PollInterval = interval
	}
	if batchSize := getEnvInt("OUTBOX_POLL_BATCH_SIZE", 0); batchSize > 0 {
		processorConfig.PollBatchSize = batchSize
	}
	if maxRetries := getEnvInt("OUTBOX_MAX_RETRIES", 0); maxRetries > 0 {
		processorConfig.MaxRetries = maxRetries
	}

	// Leader election config
	processorConfig.LeaderElection.Enabled = cfg.Leader.Enabled
	processorConfig.LeaderElection.LockName = "flowcatalyst:outbox:leader"
	processorConfig.LeaderElection.LeaseDuration = cfg.Leader.TTL
	processorConfig.LeaderElection.RefreshInterval = cfg.Leader.RefreshInterval

	// Initialize processor
	processor := outbox.NewProcessor(repo, apiClient, processorConfig)

	// Start processor
	processor.Start()
	defer processor.Stop()

	log.Info().
		Str("apiBaseURL", apiBaseURL).
		Dur("pollInterval", processorConfig.PollInterval).
		Int("batchSize", processorConfig.PollBatchSize).
		Bool("leaderElection", processorConfig.LeaderElection.Enabled).
		Msg("Outbox processor started")

	// Set up HTTP router for health/metrics only
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Recoverer)

	// Health endpoints
	r.Get("/q/health", healthChecker.HandleHealth)
	r.Get("/q/health/live", healthChecker.HandleLive)
	r.Get("/q/health/ready", healthChecker.HandleReady)

	// Prometheus metrics
	r.Handle("/metrics", promhttp.Handler())
	r.Handle("/q/metrics", promhttp.Handler())

	// Outbox processor status endpoint
	r.Get("/outbox/status", func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"enabled":%v,"apiBaseURL":"%s","pollInterval":"%s","batchSize":%d}`,
			processorConfig.Enabled,
			apiBaseURL,
			processorConfig.PollInterval,
			processorConfig.PollBatchSize)
	})

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

	log.Info().Msg("FlowCatalyst Outbox Processor stopped")
}

// maskURI masks sensitive parts of a MongoDB URI for logging
func maskURI(uri string) string {
	if len(uri) > 20 {
		return uri[:20] + "..."
	}
	return uri
}

// Helper functions for environment variables

func getEnv(key, defaultValue string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value, ok := os.LookupEnv(key); ok {
		var intVal int
		if _, err := fmt.Sscanf(value, "%d", &intVal); err == nil {
			return intVal
		}
	}
	return defaultValue
}

func getEnvDuration(key string, defaultValue time.Duration) time.Duration {
	if value, ok := os.LookupEnv(key); ok {
		if duration, err := time.ParseDuration(value); err == nil {
			return duration
		}
	}
	return defaultValue
}
