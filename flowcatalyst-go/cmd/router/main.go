// FlowCatalyst Message Router
//
// Standalone message router binary for production deployments.
// Consumes messages from queue (NATS/SQS) and delivers via HTTP mediation.

package main

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/prometheus/client_golang/prometheus/promhttp"

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
	logLevel := slog.LevelInfo
	if os.Getenv("FLOWCATALYST_DEV") == "true" {
		logLevel = slog.LevelDebug
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: logLevel})))

	slog.Info("Starting FlowCatalyst Message Router",
		"version", version,
		"build_time", buildTime,
		"component", "router")

	// Load configuration
	cfg, err := config.Load()
	if err != nil {
		slog.Error("Failed to load configuration", "error", err)
		os.Exit(1)
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
		slog.Info("Connecting to NATS server", "url", cfg.Queue.NATS.URL)
		natsClient, err := natsqueue.NewClient(&queue.NATSConfig{
			URL:        cfg.Queue.NATS.URL,
			StreamName: "DISPATCH",
		})
		if err != nil {
			slog.Error("Failed to connect to NATS server", "error", err)
			os.Exit(1)
		}
		queueCloser = natsClient.Close

		consumer, err := natsClient.CreateConsumer(ctx, "router-consumer", "dispatch.>")
		if err != nil {
			slog.Error("Failed to create NATS consumer", "error", err)
			os.Exit(1)
		}
		queueConsumer = consumer

		healthChecker.AddReadinessCheck(health.NATSCheck(func() bool {
			return true
		}))

		slog.Info("Connected to NATS server")

	case "sqs":
		slog.Info("Connecting to AWS SQS",
			"region", cfg.Queue.SQS.Region,
			"queueURL", cfg.Queue.SQS.QueueURL)

		sqsCfg := &queue.SQSConfig{
			QueueURL:            cfg.Queue.SQS.QueueURL,
			Region:              cfg.Queue.SQS.Region,
			WaitTimeSeconds:     int32(cfg.Queue.SQS.WaitTimeSeconds),
			VisibilityTimeout:   int32(cfg.Queue.SQS.VisibilityTimeout),
			MaxNumberOfMessages: 10,
		}

		sqsClient, err := sqsqueue.NewClient(ctx, sqsCfg)
		if err != nil {
			slog.Error("Failed to create SQS client", "error", err)
			os.Exit(1)
		}
		queueCloser = sqsClient.Close

		consumer, err := sqsClient.CreateConsumer(ctx, "router-consumer", "")
		if err != nil {
			slog.Error("Failed to create SQS consumer", "error", err)
			os.Exit(1)
		}
		queueConsumer = consumer

		healthChecker.AddReadinessCheck(health.SQSCheck(func() error {
			checkCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			return sqsClient.HealthCheck(checkCtx)
		}))

		slog.Info("Connected to AWS SQS")

	default:
		slog.Error("Unknown or unsupported queue type for router (use 'nats' or 'sqs')", "type", cfg.Queue.Type)
		os.Exit(1)
	}

	// Ensure queue is closed on shutdown
	if queueCloser != nil {
		defer func() {
			if err := queueCloser(); err != nil {
				slog.Error("Error closing queue", "error", err)
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
			slog.Info("Became PRIMARY - starting message processing")
			if messageRouter != nil {
				messageRouter.Start()
			}
		},
		OnBecomeStandby: func() {
			slog.Info("Became STANDBY - stopping message processing")
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
		slog.Error("Failed to start standby service", "error", err)
		os.Exit(1)
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
		slog.Info("HTTP server starting", "port", cfg.HTTP.Port)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("HTTP server failed", "error", err)
			os.Exit(1)
		}
	}()

	// Wait for interrupt signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	slog.Info("Shutting down gracefully...")

	// Graceful shutdown
	shutdownCtx, shutdownCancel := context.WithTimeout(ctx, 30*time.Second)
	defer shutdownCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		slog.Error("HTTP server forced to shutdown", "error", err)
	}

	slog.Info("FlowCatalyst Message Router stopped")
}
