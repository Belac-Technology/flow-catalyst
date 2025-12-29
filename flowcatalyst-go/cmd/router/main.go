// FlowCatalyst Message Router
//
// Standalone message router binary for production deployments.
// Consumes messages from queue (NATS/SQS) and delivers via HTTP mediation.

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

	"go.flowcatalyst.tech/internal/common/health"
	"go.flowcatalyst.tech/internal/config"
	"go.flowcatalyst.tech/internal/queue"
	natsqueue "go.flowcatalyst.tech/internal/queue/nats"
	sqsqueue "go.flowcatalyst.tech/internal/queue/sqs"
	"go.flowcatalyst.tech/internal/router/manager"
	"go.flowcatalyst.tech/internal/router/mediator"
	"go.flowcatalyst.tech/internal/router/standby"
	"go.flowcatalyst.tech/internal/router/warning"
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
		Str("component", "router").
		Msg("Starting FlowCatalyst Message Router")

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

	// Initialize queue consumer based on configuration
	var queueConsumer queue.Consumer
	var queueCloser func() error

	switch cfg.Queue.Type {
	case "nats":
		log.Info().Str("url", cfg.Queue.NATS.URL).Msg("Connecting to NATS server")
		natsClient, err := natsqueue.NewClient(&queue.NATSConfig{
			URL:        cfg.Queue.NATS.URL,
			StreamName: "DISPATCH",
		})
		if err != nil {
			log.Fatal().Err(err).Msg("Failed to connect to NATS server")
		}
		queueCloser = natsClient.Close

		consumer, err := natsClient.CreateConsumer(ctx, "router-consumer", "dispatch.>")
		if err != nil {
			log.Fatal().Err(err).Msg("Failed to create NATS consumer")
		}
		queueConsumer = consumer

		healthChecker.AddReadinessCheck(health.NATSCheck(func() bool {
			return true
		}))

		log.Info().Msg("Connected to NATS server")

	case "sqs":
		log.Info().
			Str("region", cfg.Queue.SQS.Region).
			Str("queueURL", cfg.Queue.SQS.QueueURL).
			Msg("Connecting to AWS SQS")

		sqsCfg := &queue.SQSConfig{
			QueueURL:            cfg.Queue.SQS.QueueURL,
			Region:              cfg.Queue.SQS.Region,
			WaitTimeSeconds:     int32(cfg.Queue.SQS.WaitTimeSeconds),
			VisibilityTimeout:   int32(cfg.Queue.SQS.VisibilityTimeout),
			MaxNumberOfMessages: 10,
		}

		sqsClient, err := sqsqueue.NewClient(ctx, sqsCfg)
		if err != nil {
			log.Fatal().Err(err).Msg("Failed to create SQS client")
		}
		queueCloser = sqsClient.Close

		consumer, err := sqsClient.CreateConsumer(ctx, "router-consumer", "")
		if err != nil {
			log.Fatal().Err(err).Msg("Failed to create SQS consumer")
		}
		queueConsumer = consumer

		healthChecker.AddReadinessCheck(health.SQSCheck(func() error {
			checkCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			return sqsClient.HealthCheck(checkCtx)
		}))

		log.Info().Msg("Connected to AWS SQS")

	default:
		log.Fatal().Str("type", cfg.Queue.Type).Msg("Unknown or unsupported queue type for router (use 'nats' or 'sqs')")
	}

	// Ensure queue is closed on shutdown
	if queueCloser != nil {
		defer func() {
			if err := queueCloser(); err != nil {
				log.Error().Err(err).Msg("Error closing queue")
			}
		}()
	}

	// Initialize standby service for leader election
	standbyCfg := &standby.Config{
		Enabled:         cfg.Leader.Enabled,
		InstanceID:      cfg.Leader.InstanceID,
		LockKey:         "flowcatalyst:router:leader",
		LockTTL:         cfg.Leader.TTL,
		RefreshInterval: cfg.Leader.RefreshInterval,
	}

	var messageRouter *manager.Router

	standbyCallbacks := &standby.Callbacks{
		OnBecomePrimary: func() {
			log.Info().Msg("Became PRIMARY - starting message processing")
			if messageRouter != nil {
				messageRouter.Start()
			}
		},
		OnBecomeStandby: func() {
			log.Info().Msg("Became STANDBY - stopping message processing")
			if messageRouter != nil {
				messageRouter.Stop()
			}
		},
	}

	standbyService := standby.NewService(standbyCfg, standbyCallbacks)

	// Initialize message router
	mediatorCfg := mediator.DefaultHTTPMediatorConfig()
	messageRouter = manager.NewRouter(queueConsumer, mediatorCfg)

	// Start standby service (handles leader election)
	if err := standbyService.Start(); err != nil {
		log.Fatal().Err(err).Msg("Failed to start standby service")
	}
	defer standbyService.Stop()

	// If standby is disabled, start router immediately
	if !cfg.Leader.Enabled {
		messageRouter.Start()
		defer messageRouter.Stop()
	}

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

	// Standby status endpoint
	r.Get("/router/status", func(w http.ResponseWriter, req *http.Request) {
		status := standbyService.GetStatus()
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"role":"%s","instanceId":"%s","standbyEnabled":%v}`,
			standbyService.GetRole(), standbyService.GetInstanceID(), status.StandbyEnabled)
	})

	// Initialize warning service and handler
	warningService := warning.NewInMemoryService()
	warningHandler := warning.NewHandler(warningService)
	warningHandler.RegisterRoutes(r)

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

	log.Info().Msg("FlowCatalyst Message Router stopped")
}
