package outbox

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/rs/zerolog/log"

	"go.flowcatalyst.tech/internal/common/leader"
)

// ProcessorConfig holds configuration for the outbox processor
type ProcessorConfig struct {
	// Enabled controls whether the processor is active
	Enabled bool

	// PollInterval is how often to poll for pending items
	PollInterval time.Duration

	// PollBatchSize is the maximum items to fetch per poll
	PollBatchSize int

	// APIBatchSize is the maximum items per API call
	APIBatchSize int

	// MaxConcurrentGroups limits parallel message group processing
	MaxConcurrentGroups int

	// GlobalBufferSize is the in-memory buffer capacity
	GlobalBufferSize int

	// MaxRetries is the maximum retry attempts before marking as failed
	MaxRetries int

	// ProcessingTimeoutSeconds is how long before stuck items are recovered
	ProcessingTimeoutSeconds int

	// RecoveryInterval is how often to run crash recovery
	RecoveryInterval time.Duration

	// LeaderElection enables distributed leader election
	LeaderElection LeaderElectionConfig
}

// LeaderElectionConfig holds leader election settings
type LeaderElectionConfig struct {
	Enabled         bool
	LockName        string
	LeaseDuration   time.Duration
	RefreshInterval time.Duration
}

// DefaultProcessorConfig returns sensible defaults
func DefaultProcessorConfig() *ProcessorConfig {
	return &ProcessorConfig{
		Enabled:                  true,
		PollInterval:             time.Second,
		PollBatchSize:            500,
		APIBatchSize:             100,
		MaxConcurrentGroups:      10,
		GlobalBufferSize:         1000,
		MaxRetries:               3,
		ProcessingTimeoutSeconds: 300,
		RecoveryInterval:         time.Minute,
	}
}

// Processor implements the Outbox Pattern for reliable message publishing.
// It polls customer databases for pending outbox items and sends them to FlowCatalyst APIs.
//
// Architecture:
//
//	OutboxPoller (scheduled) → GlobalBuffer → GroupDistributor → MessageGroupProcessor → API
//
// This matches Java's tech.flowcatalyst.outboxprocessor
type Processor struct {
	config    *ProcessorConfig
	repo      Repository
	apiClient *APIClient

	// Global buffer for backpressure
	buffer     chan *OutboxItem
	bufferSize int32

	// Group distributor
	groupProcessors sync.Map // map[groupKey]*MessageGroupProcessor
	groupSemaphore  chan struct{}

	// Leader election
	leaderElector *leader.LeaderElector
	isPrimary     atomic.Bool

	// Lifecycle
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	running    bool
	runningMu  sync.Mutex
	pollMu     sync.Mutex // Prevent overlapping polls
}

// NewProcessor creates a new outbox processor
func NewProcessor(repo Repository, apiClient *APIClient, config *ProcessorConfig) *Processor {
	if config == nil {
		config = DefaultProcessorConfig()
	}

	ctx, cancel := context.WithCancel(context.Background())

	p := &Processor{
		config:         config,
		repo:           repo,
		apiClient:      apiClient,
		buffer:         make(chan *OutboxItem, config.GlobalBufferSize),
		groupSemaphore: make(chan struct{}, config.MaxConcurrentGroups),
		ctx:            ctx,
		cancel:         cancel,
	}

	// Initialize leader elector if enabled
	if config.LeaderElection.Enabled {
		// Note: In a real implementation, you'd pass the MongoDB client here
		// For now, we assume single-instance deployment
		p.isPrimary.Store(true)
	} else {
		p.isPrimary.Store(true)
	}

	return p
}

// Start starts the outbox processor
func (p *Processor) Start() {
	p.runningMu.Lock()
	defer p.runningMu.Unlock()

	if p.running {
		return
	}
	p.running = true

	if !p.config.Enabled {
		log.Info().Msg("Outbox processor is disabled")
		return
	}

	// Start the distributor goroutine
	p.wg.Add(1)
	go p.runDistributor()

	// Start the polling goroutine
	p.wg.Add(1)
	go p.runPoller()

	// Start the recovery goroutine
	p.wg.Add(1)
	go p.runRecovery()

	log.Info().
		Dur("pollInterval", p.config.PollInterval).
		Int("batchSize", p.config.PollBatchSize).
		Int("maxConcurrentGroups", p.config.MaxConcurrentGroups).
		Msg("Outbox processor started")
}

// Stop stops the outbox processor
func (p *Processor) Stop() {
	p.runningMu.Lock()
	p.running = false
	p.runningMu.Unlock()

	p.cancel()
	p.wg.Wait()

	log.Info().Msg("Outbox processor stopped")
}

// runPoller runs the main polling loop
func (p *Processor) runPoller() {
	defer p.wg.Done()

	ticker := time.NewTicker(p.config.PollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-p.ctx.Done():
			return
		case <-ticker.C:
			if !p.isPrimary.Load() {
				continue
			}
			p.doPoll()
		}
	}
}

// doPoll performs a single poll iteration
func (p *Processor) doPoll() {
	// Prevent overlapping polls
	if !p.pollMu.TryLock() {
		return
	}
	defer p.pollMu.Unlock()

	ctx, cancel := context.WithTimeout(p.ctx, 30*time.Second)
	defer cancel()

	// Poll for events
	p.pollItemType(ctx, OutboxItemTypeEvent)

	// Poll for dispatch jobs
	p.pollItemType(ctx, OutboxItemTypeDispatchJob)
}

// pollItemType polls for items of a specific type
func (p *Processor) pollItemType(ctx context.Context, itemType OutboxItemType) {
	items, err := p.repo.FetchAndLockPending(ctx, itemType, p.config.PollBatchSize)
	if err != nil {
		log.Error().Err(err).
			Str("type", string(itemType)).
			Msg("Failed to fetch pending outbox items")
		return
	}

	if len(items) == 0 {
		return
	}

	log.Debug().
		Str("type", string(itemType)).
		Int("count", len(items)).
		Msg("Fetched pending outbox items")

	// Add to buffer
	added := 0
	rejected := 0
	for _, item := range items {
		select {
		case p.buffer <- item:
			added++
			atomic.AddInt32(&p.bufferSize, 1)
		default:
			// Buffer full - item stays in PROCESSING, will be recovered later
			rejected++
		}
	}

	if rejected > 0 {
		log.Warn().
			Int("added", added).
			Int("rejected", rejected).
			Msg("Buffer full, some items rejected")
	}
}

// runDistributor runs the distributor loop that routes items to group processors
func (p *Processor) runDistributor() {
	defer p.wg.Done()

	for {
		select {
		case <-p.ctx.Done():
			// Drain remaining items
			p.drainBuffer()
			return
		case item := <-p.buffer:
			atomic.AddInt32(&p.bufferSize, -1)
			p.distributeItem(item)
		}
	}
}

// distributeItem routes an item to the appropriate message group processor
func (p *Processor) distributeItem(item *OutboxItem) {
	groupKey := fmt.Sprintf("%s:%s", item.Type, item.GetEffectiveMessageGroup())

	// Get or create processor for this group
	processorI, _ := p.groupProcessors.LoadOrStore(groupKey, &MessageGroupProcessor{
		groupKey:   groupKey,
		itemType:   item.Type,
		queue:      make(chan *OutboxItem, 100),
		processor:  p,
		processing: false,
	})
	processor := processorI.(*MessageGroupProcessor)

	// Add item to group queue
	select {
	case processor.queue <- item:
		// Try to start processing if not already running
		processor.tryStart()
	default:
		// Group queue full - this shouldn't happen often
		log.Warn().
			Str("group", groupKey).
			Str("itemId", item.ID).
			Msg("Group queue full")
	}
}

// drainBuffer drains remaining items from the buffer during shutdown
func (p *Processor) drainBuffer() {
	for {
		select {
		case item := <-p.buffer:
			log.Debug().
				Str("itemId", item.ID).
				Msg("Draining item during shutdown")
		default:
			return
		}
	}
}

// runRecovery runs the crash recovery loop
func (p *Processor) runRecovery() {
	defer p.wg.Done()

	ticker := time.NewTicker(p.config.RecoveryInterval)
	defer ticker.Stop()

	for {
		select {
		case <-p.ctx.Done():
			return
		case <-ticker.C:
			if !p.isPrimary.Load() {
				continue
			}
			p.doRecovery()
		}
	}
}

// doRecovery recovers stuck items
func (p *Processor) doRecovery() {
	ctx, cancel := context.WithTimeout(p.ctx, 30*time.Second)
	defer cancel()

	for _, itemType := range []OutboxItemType{OutboxItemTypeEvent, OutboxItemTypeDispatchJob} {
		recovered, err := p.repo.RecoverStuckItems(ctx, itemType, p.config.ProcessingTimeoutSeconds)
		if err != nil {
			log.Error().Err(err).
				Str("type", string(itemType)).
				Msg("Failed to recover stuck items")
			continue
		}
		if recovered > 0 {
			log.Info().
				Str("type", string(itemType)).
				Int64("count", recovered).
				Msg("Recovered stuck outbox items")
		}
	}
}

// MessageGroupProcessor processes items for a single message group
type MessageGroupProcessor struct {
	groupKey   string
	itemType   OutboxItemType
	queue      chan *OutboxItem
	processor  *Processor
	processing bool
	mu         sync.Mutex
}

// tryStart attempts to start processing if not already running
func (m *MessageGroupProcessor) tryStart() {
	m.mu.Lock()
	if m.processing {
		m.mu.Unlock()
		return
	}
	m.processing = true
	m.mu.Unlock()

	go m.processLoop()
}

// processLoop processes items in the group queue
func (m *MessageGroupProcessor) processLoop() {
	defer func() {
		m.mu.Lock()
		m.processing = false
		m.mu.Unlock()
	}()

	for {
		// Acquire semaphore
		select {
		case m.processor.groupSemaphore <- struct{}{}:
			// Got semaphore
		case <-m.processor.ctx.Done():
			return
		}

		// Process a batch
		batch := m.collectBatch()
		if len(batch) == 0 {
			<-m.processor.groupSemaphore // Release semaphore
			return                        // No more items, exit
		}

		m.processBatch(batch)
		<-m.processor.groupSemaphore // Release semaphore
	}
}

// collectBatch collects up to APIBatchSize items from the queue
func (m *MessageGroupProcessor) collectBatch() []*OutboxItem {
	batch := make([]*OutboxItem, 0, m.processor.config.APIBatchSize)

	for i := 0; i < m.processor.config.APIBatchSize; i++ {
		select {
		case item := <-m.queue:
			batch = append(batch, item)
		default:
			return batch
		}
	}

	return batch
}

// processBatch sends a batch to the API
func (m *MessageGroupProcessor) processBatch(batch []*OutboxItem) {
	if len(batch) == 0 {
		return
	}

	ctx, cancel := context.WithTimeout(m.processor.ctx, 30*time.Second)
	defer cancel()

	var result *BatchResult
	var err error

	switch m.itemType {
	case OutboxItemTypeEvent:
		result, err = m.processor.apiClient.SendEventBatch(ctx, batch)
	case OutboxItemTypeDispatchJob:
		result, err = m.processor.apiClient.SendDispatchJobBatch(ctx, batch)
	}

	if err != nil {
		log.Error().Err(err).
			Str("group", m.groupKey).
			Int("batchSize", len(batch)).
			Msg("Failed to send batch")
		m.handleFailure(ctx, batch, err.Error())
		return
	}

	// Mark successful items as completed
	if len(result.SuccessIDs) > 0 {
		if err := m.processor.repo.MarkCompleted(ctx, m.itemType, result.SuccessIDs); err != nil {
			log.Error().Err(err).Msg("Failed to mark items as completed")
		}
	}

	// Handle failed items
	if len(result.FailedIDs) > 0 {
		m.handleFailure(ctx, batch, "API batch partially failed")
	}

	log.Debug().
		Str("group", m.groupKey).
		Int("success", len(result.SuccessIDs)).
		Int("failed", len(result.FailedIDs)).
		Msg("Batch processed")
}

// handleFailure handles failed items (retry or mark as failed)
func (m *MessageGroupProcessor) handleFailure(ctx context.Context, items []*OutboxItem, errorMsg string) {
	retryIDs := make([]string, 0)
	failIDs := make([]string, 0)

	for _, item := range items {
		if item.RetryCount < m.processor.config.MaxRetries {
			retryIDs = append(retryIDs, item.ID)
		} else {
			failIDs = append(failIDs, item.ID)
		}
	}

	if len(retryIDs) > 0 {
		if err := m.processor.repo.ScheduleRetry(ctx, m.itemType, retryIDs); err != nil {
			log.Error().Err(err).Msg("Failed to schedule retry")
		}
	}

	if len(failIDs) > 0 {
		if err := m.processor.repo.MarkFailed(ctx, m.itemType, failIDs, errorMsg); err != nil {
			log.Error().Err(err).Msg("Failed to mark items as failed")
		}
		log.Warn().
			Str("group", m.groupKey).
			Int("count", len(failIDs)).
			Msg("Items marked as FAILED (max retries exceeded)")
	}
}

