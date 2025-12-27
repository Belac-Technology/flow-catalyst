// FlowCatalyst Stream Processor
//
// Standalone stream processor binary for production deployments.
// Watches MongoDB change streams and builds read-model projections.

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
	"go.flowcatalyst.tech/internal/stream"
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
		Str("component", "stream").
		Msg("Starting FlowCatalyst Stream Processor")

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

	// Initialize stream processor
	streamCfg := stream.DefaultProcessorConfig()
	streamCfg.Database = cfg.MongoDB.Database
	streamProcessor := stream.NewProcessor(mongoClient, streamCfg)

	// Create indexes for projections
	if err := streamProcessor.EnsureIndexes(ctx); err != nil {
		log.Warn().Err(err).Msg("Failed to ensure projection indexes")
	}

	// Start stream processor
	if err := streamProcessor.Start(); err != nil {
		log.Fatal().Err(err).Msg("Failed to start stream processor")
	}
	defer streamProcessor.Stop()

	// Add stream processor health check
	healthChecker.AddReadinessCheck(streamProcessor.HealthCheck())

	log.Info().Msg("Stream processor started")

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

	// Stream processor status endpoint
	r.Get("/stream/status", func(w http.ResponseWriter, req *http.Request) {
		running := streamProcessor.IsRunning()
		watchers := streamProcessor.GetWatcherStatusMap()
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"running":%v,"watchers":%d,"healthy":%v}`,
			running, len(watchers), running)
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

	log.Info().Msg("FlowCatalyst Stream Processor stopped")
}

// maskURI masks sensitive parts of a MongoDB URI for logging
func maskURI(uri string) string {
	if len(uri) > 20 {
		return uri[:20] + "..."
	}
	return uri
}
