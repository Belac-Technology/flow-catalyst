package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

var (
	// Pool metrics

	// PoolMessagesProcessed tracks total messages processed by pool
	PoolMessagesProcessed = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "pool",
			Name:      "messages_processed_total",
			Help:      "Total messages processed by dispatch pool",
		},
		[]string{"pool_code", "result"}, // result: success, failed, rate_limited
	)

	// PoolProcessingDuration tracks message processing duration
	PoolProcessingDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Namespace: "flowcatalyst",
			Subsystem: "pool",
			Name:      "processing_duration_seconds",
			Help:      "Time to process a message",
			Buckets:   prometheus.DefBuckets,
		},
		[]string{"pool_code"},
	)

	// PoolActiveWorkers tracks number of active workers
	PoolActiveWorkers = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Namespace: "flowcatalyst",
			Subsystem: "pool",
			Name:      "active_workers",
			Help:      "Number of active workers in the pool",
		},
		[]string{"pool_code"},
	)

	// PoolQueueDepth tracks queue depth (pending messages)
	PoolQueueDepth = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Namespace: "flowcatalyst",
			Subsystem: "pool",
			Name:      "queue_depth",
			Help:      "Number of messages pending in the pool queue",
		},
		[]string{"pool_code"},
	)

	// PoolRateLimitRejections tracks rate limit rejections
	PoolRateLimitRejections = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "pool",
			Name:      "rate_limit_rejections_total",
			Help:      "Total messages rejected due to rate limiting",
		},
		[]string{"pool_code"},
	)

	// PoolAvailablePermits tracks available concurrency permits
	PoolAvailablePermits = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Namespace: "flowcatalyst",
			Subsystem: "pool",
			Name:      "available_permits",
			Help:      "Available concurrency permits in the pool",
		},
		[]string{"pool_code"},
	)

	// PoolMessageGroupCount tracks active message groups
	PoolMessageGroupCount = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Namespace: "flowcatalyst",
			Subsystem: "pool",
			Name:      "message_group_count",
			Help:      "Number of active message groups in the pool",
		},
		[]string{"pool_code"},
	)

	// Mediator metrics

	// MediatorHTTPRequests tracks HTTP requests made by the mediator
	MediatorHTTPRequests = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "mediator",
			Name:      "http_requests_total",
			Help:      "Total HTTP requests made by the mediator",
		},
		[]string{"status_code", "method"},
	)

	// MediatorHTTPDuration tracks HTTP request duration
	MediatorHTTPDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Namespace: "flowcatalyst",
			Subsystem: "mediator",
			Name:      "http_duration_seconds",
			Help:      "HTTP request duration",
			Buckets:   []float64{0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
		},
		[]string{"target"},
	)

	// MediatorCircuitBreakerState tracks circuit breaker state
	// 0 = closed (healthy), 1 = open (tripped), 2 = half-open (testing)
	MediatorCircuitBreakerState = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Namespace: "flowcatalyst",
			Subsystem: "mediator",
			Name:      "circuit_breaker_state",
			Help:      "Circuit breaker state (0=closed, 1=open, 2=half-open)",
		},
		[]string{"target"},
	)

	// MediatorCircuitBreakerTrips tracks circuit breaker trip events
	MediatorCircuitBreakerTrips = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "mediator",
			Name:      "circuit_breaker_trips_total",
			Help:      "Total circuit breaker trip events",
		},
		[]string{"target"},
	)

	// Scheduler metrics

	// SchedulerJobsScheduled tracks total jobs scheduled
	SchedulerJobsScheduled = promauto.NewCounter(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "scheduler",
			Name:      "jobs_scheduled_total",
			Help:      "Total jobs scheduled for dispatch",
		},
	)

	// SchedulerJobsPending tracks pending jobs
	SchedulerJobsPending = promauto.NewGauge(
		prometheus.GaugeOpts{
			Namespace: "flowcatalyst",
			Subsystem: "scheduler",
			Name:      "jobs_pending",
			Help:      "Number of jobs pending dispatch",
		},
	)

	// SchedulerStaleJobs tracks stale jobs recovered
	SchedulerStaleJobs = promauto.NewCounter(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "scheduler",
			Name:      "stale_jobs_recovered_total",
			Help:      "Total stale jobs recovered",
		},
	)

	// Stream processor metrics

	// StreamEventsProcessed tracks events processed by stream processor
	StreamEventsProcessed = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "stream",
			Name:      "events_processed_total",
			Help:      "Total events processed by stream processor",
		},
		[]string{"event_type", "result"}, // result: success, failed
	)

	// StreamProcessingDuration tracks event processing duration
	StreamProcessingDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Namespace: "flowcatalyst",
			Subsystem: "stream",
			Name:      "processing_duration_seconds",
			Help:      "Time to process an event",
			Buckets:   prometheus.DefBuckets,
		},
		[]string{"event_type"},
	)

	// StreamLag tracks stream consumer lag
	StreamLag = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Namespace: "flowcatalyst",
			Subsystem: "stream",
			Name:      "consumer_lag",
			Help:      "Number of messages behind in the stream",
		},
		[]string{"stream_name"},
	)

	// Queue metrics

	// QueueMessagesPublished tracks messages published to queue
	QueueMessagesPublished = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "queue",
			Name:      "messages_published_total",
			Help:      "Total messages published to queue",
		},
		[]string{"queue_type"}, // nats, sqs
	)

	// QueueMessagesConsumed tracks messages consumed from queue
	QueueMessagesConsumed = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "queue",
			Name:      "messages_consumed_total",
			Help:      "Total messages consumed from queue",
		},
		[]string{"queue_type"}, // nats, sqs
	)

	// QueuePublishErrors tracks queue publish errors
	QueuePublishErrors = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "queue",
			Name:      "publish_errors_total",
			Help:      "Total queue publish errors",
		},
		[]string{"queue_type"},
	)

	// HTTP API metrics

	// HTTPRequestsTotal tracks HTTP API requests
	HTTPRequestsTotal = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Namespace: "flowcatalyst",
			Subsystem: "http",
			Name:      "requests_total",
			Help:      "Total HTTP API requests",
		},
		[]string{"method", "path", "status"},
	)

	// HTTPRequestDuration tracks HTTP API request duration
	HTTPRequestDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Namespace: "flowcatalyst",
			Subsystem: "http",
			Name:      "request_duration_seconds",
			Help:      "HTTP API request duration",
			Buckets:   prometheus.DefBuckets,
		},
		[]string{"method", "path"},
	)

	// HTTPActiveConnections tracks active HTTP connections
	HTTPActiveConnections = promauto.NewGauge(
		prometheus.GaugeOpts{
			Namespace: "flowcatalyst",
			Subsystem: "http",
			Name:      "active_connections",
			Help:      "Number of active HTTP connections",
		},
	)
)

// CircuitBreakerState constants
const (
	CircuitBreakerClosed   = 0
	CircuitBreakerOpen     = 1
	CircuitBreakerHalfOpen = 2
)
