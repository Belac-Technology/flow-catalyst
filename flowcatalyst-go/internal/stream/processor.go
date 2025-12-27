// Package stream provides MongoDB change stream processing
package stream

import (
	"context"
	"sync"
	"time"

	"github.com/rs/zerolog/log"
	"go.mongodb.org/mongo-driver/mongo"

	"go.flowcatalyst.tech/internal/common/health"
)

// ProcessorConfig holds configuration for the stream processor
type ProcessorConfig struct {
	// Database is the MongoDB database name
	Database string

	// EventsEnabled enables the events projection stream
	EventsEnabled bool

	// DispatchJobsEnabled enables the dispatch jobs projection stream
	DispatchJobsEnabled bool

	// BatchMaxSize is the maximum batch size before flushing
	BatchMaxSize int

	// BatchMaxWait is the maximum time to wait before flushing a batch
	BatchMaxWait time.Duration
}

// DefaultProcessorConfig returns sensible defaults
func DefaultProcessorConfig() *ProcessorConfig {
	return &ProcessorConfig{
		Database:            "flowcatalyst",
		EventsEnabled:       true,
		DispatchJobsEnabled: true,
		BatchMaxSize:        100,
		BatchMaxWait:        5 * time.Second,
	}
}

// Processor manages multiple MongoDB change stream watchers
type Processor struct {
	client          *mongo.Client
	config          *ProcessorConfig
	checkpointStore CheckpointStore
	watchers        []*Watcher
	running         bool
	runningMu       sync.Mutex
}

// NewProcessor creates a new stream processor
func NewProcessor(client *mongo.Client, config *ProcessorConfig) *Processor {
	if config == nil {
		config = DefaultProcessorConfig()
	}

	db := client.Database(config.Database)
	checkpointStore := NewMongoCheckpointStore(db)

	return &Processor{
		client:          client,
		config:          config,
		checkpointStore: checkpointStore,
		watchers:        make([]*Watcher, 0),
	}
}

// Start starts all configured stream watchers
func (p *Processor) Start() error {
	p.runningMu.Lock()
	if p.running {
		p.runningMu.Unlock()
		log.Warn().Msg("Stream processor already running")
		return nil
	}
	p.running = true
	p.runningMu.Unlock()

	log.Info().Msg("Starting stream processor")

	// Create events stream watcher
	if p.config.EventsEnabled {
		eventsConfig := &StreamConfig{
			Name:             "events",
			SourceCollection: "events",
			TargetCollection: "events_read",
			WatchOperations:  []string{"insert", "update", "replace"},
			BatchMaxSize:     p.config.BatchMaxSize,
			BatchMaxWait:     p.config.BatchMaxWait,
			CheckpointKey:    "events_projection",
		}

		eventsWatcher := NewWatcher(
			p.client,
			p.config.Database,
			eventsConfig,
			p.checkpointStore,
			NewEventProjectionMapper(),
		)
		p.watchers = append(p.watchers, eventsWatcher)
		eventsWatcher.Start()

		log.Info().
			Str("source", eventsConfig.SourceCollection).
			Str("target", eventsConfig.TargetCollection).
			Msg("Events stream watcher started")
	}

	// Create dispatch jobs stream watcher
	if p.config.DispatchJobsEnabled {
		dispatchJobsConfig := &StreamConfig{
			Name:             "dispatch_jobs",
			SourceCollection: "dispatch_jobs",
			TargetCollection: "dispatch_jobs_read",
			WatchOperations:  []string{"insert", "update", "replace"},
			BatchMaxSize:     p.config.BatchMaxSize,
			BatchMaxWait:     p.config.BatchMaxWait,
			CheckpointKey:    "dispatch_jobs_projection",
		}

		dispatchJobsWatcher := NewWatcher(
			p.client,
			p.config.Database,
			dispatchJobsConfig,
			p.checkpointStore,
			NewDispatchJobProjectionMapper(),
		)
		p.watchers = append(p.watchers, dispatchJobsWatcher)
		dispatchJobsWatcher.Start()

		log.Info().
			Str("source", dispatchJobsConfig.SourceCollection).
			Str("target", dispatchJobsConfig.TargetCollection).
			Msg("Dispatch jobs stream watcher started")
	}

	log.Info().
		Int("watcherCount", len(p.watchers)).
		Msg("Stream processor started")

	return nil
}

// Stop stops all stream watchers
func (p *Processor) Stop() {
	p.runningMu.Lock()
	if !p.running {
		p.runningMu.Unlock()
		return
	}
	p.running = false
	p.runningMu.Unlock()

	log.Info().Msg("Stopping stream processor")

	// Stop all watchers concurrently
	var wg sync.WaitGroup
	for _, w := range p.watchers {
		wg.Add(1)
		go func(watcher *Watcher) {
			defer wg.Done()
			watcher.Stop()
		}(w)
	}
	wg.Wait()

	p.watchers = make([]*Watcher, 0)

	log.Info().Msg("Stream processor stopped")
}

// IsRunning returns true if the processor is running
func (p *Processor) IsRunning() bool {
	p.runningMu.Lock()
	defer p.runningMu.Unlock()
	return p.running
}

// GetWatcherStatus returns status information for all watchers
func (p *Processor) GetWatcherStatus() []WatcherStatus {
	statuses := make([]WatcherStatus, 0, len(p.watchers))
	for _, w := range p.watchers {
		statuses = append(statuses, WatcherStatus{
			Name:    w.name,
			Running: w.IsRunning(),
		})
	}
	return statuses
}

// WatcherStatus holds status information for a watcher
type WatcherStatus struct {
	Name    string `json:"name"`
	Running bool   `json:"running"`
}

// StreamMetrics holds detailed metrics for a stream watcher (like Java's StreamContext)
type StreamMetrics struct {
	WatcherName      string `json:"watcherName"`
	Running          bool   `json:"running"`
	HasFatalError    bool   `json:"hasFatalError"`
	FatalError       string `json:"fatalError,omitempty"`
	BatchesProcessed int64  `json:"batchesProcessed"`
	CheckpointedSeq  int64  `json:"checkpointedSeq"`
	InFlightCount    int32  `json:"inFlightCount"`
	AvailableSlots   int32  `json:"availableSlots"`
}

// HealthCheck returns a health check function for the stream processor
func (p *Processor) HealthCheck() health.CheckFunc {
	return health.StreamProcessorCheckDetailed(
		p.IsRunning,
		func() interface{} {
			// Convert to interface slice to avoid type issues
			metrics := p.GetStreamMetrics()
			result := make([]health.StreamMetricsData, len(metrics))
			for i, m := range metrics {
				result[i] = health.StreamMetricsData{
					WatcherName:      m.WatcherName,
					Running:          m.Running,
					HasFatalError:    m.HasFatalError,
					FatalError:       m.FatalError,
					BatchesProcessed: m.BatchesProcessed,
					CheckpointedSeq:  m.CheckpointedSeq,
					InFlightCount:    m.InFlightCount,
					AvailableSlots:   m.AvailableSlots,
				}
			}
			return result
		},
	)
}

// GetWatcherStatusMap returns a map of watcher names to running status
func (p *Processor) GetWatcherStatusMap() map[string]bool {
	statuses := make(map[string]bool)
	for _, w := range p.watchers {
		statuses[w.name] = w.IsRunning()
	}
	return statuses
}

// GetStreamMetrics returns detailed metrics for all stream watchers
func (p *Processor) GetStreamMetrics() []StreamMetrics {
	result := make([]StreamMetrics, 0, len(p.watchers))
	for _, w := range p.watchers {
		m := StreamMetrics{
			WatcherName:      w.name,
			Running:          w.IsRunning(),
			HasFatalError:    w.HasFatalError(),
			BatchesProcessed: w.GetCurrentBatchSequence(),
			CheckpointedSeq:  w.GetLastCheckpointedSequence(),
			InFlightCount:    w.GetInFlightBatchCount(),
			AvailableSlots:   w.GetAvailableConcurrencySlots(),
		}
		if w.HasFatalError() {
			m.FatalError = w.GetFatalError().Error()
		}
		result = append(result, m)
	}
	return result
}

// EnsureIndexes creates necessary indexes on projection collections
func (p *Processor) EnsureIndexes(ctx context.Context) error {
	db := p.client.Database(p.config.Database)

	// Events read projection indexes
	eventsReadColl := db.Collection("events_read")
	eventsIndexes := []mongo.IndexModel{
		{Keys: map[string]interface{}{"clientId": 1, "createdAt": -1}},
		{Keys: map[string]interface{}{"applicationCode": 1, "createdAt": -1}},
		{Keys: map[string]interface{}{"type": 1, "createdAt": -1}},
		{Keys: map[string]interface{}{"contextData.correlationId": 1}},
		{Keys: map[string]interface{}{"projectedAt": 1}},
	}

	if _, err := eventsReadColl.Indexes().CreateMany(ctx, eventsIndexes); err != nil {
		log.Error().Err(err).Msg("Failed to create events_read indexes")
		return err
	}

	// Dispatch jobs read projection indexes
	dispatchJobsReadColl := db.Collection("dispatch_jobs_read")
	dispatchJobsIndexes := []mongo.IndexModel{
		{Keys: map[string]interface{}{"clientId": 1, "createdAt": -1}},
		{Keys: map[string]interface{}{"applicationCode": 1, "createdAt": -1}},
		{Keys: map[string]interface{}{"status": 1, "createdAt": -1}},
		{Keys: map[string]interface{}{"eventId": 1}},
		{Keys: map[string]interface{}{"subscriptionId": 1, "createdAt": -1}},
		{Keys: map[string]interface{}{"dispatchPoolId": 1, "status": 1}},
		{Keys: map[string]interface{}{"metadata.correlationId": 1}},
		{Keys: map[string]interface{}{"projectedAt": 1}},
	}

	if _, err := dispatchJobsReadColl.Indexes().CreateMany(ctx, dispatchJobsIndexes); err != nil {
		log.Error().Err(err).Msg("Failed to create dispatch_jobs_read indexes")
		return err
	}

	log.Info().Msg("Projection indexes created")
	return nil
}
