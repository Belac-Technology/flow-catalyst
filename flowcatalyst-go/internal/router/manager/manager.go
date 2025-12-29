// Package manager provides the queue manager for the message router
package manager

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/rs/zerolog/log"
	"go.mongodb.org/mongo-driver/mongo"

	"go.flowcatalyst.tech/internal/common/metrics"
	"go.flowcatalyst.tech/internal/common/tsid"
	"go.flowcatalyst.tech/internal/platform/dispatchpool"
	"go.flowcatalyst.tech/internal/queue"
	"go.flowcatalyst.tech/internal/router/mediator"
	"go.flowcatalyst.tech/internal/router/model"
	"go.flowcatalyst.tech/internal/router/pool"
)

// Default pool configuration constants (matching Java)
const (
	DefaultPoolConcurrency     = 20  // Java: DEFAULT_POOL_CONCURRENCY = 20
	DefaultQueueCapacityMultiplier = 2   // Java: QUEUE_CAPACITY_MULTIPLIER = 2
	MinQueueCapacity           = 50  // Java: MIN_QUEUE_CAPACITY = 50
	DefaultPoolCode            = "DEFAULT-POOL"
)

// StandbyChecker interface for checking if this instance is the primary
// This matches Java's StandbyService.isPrimary() check
type StandbyChecker interface {
	// IsPrimary returns true if this instance is the active leader
	IsPrimary() bool
}

// PoolConfig holds configuration for a processing pool
type PoolConfig struct {
	Code               string
	Concurrency        int
	QueueCapacity      int
	RateLimitPerMinute *int
}

// ConfigSyncConfig holds configuration for pool config sync
type ConfigSyncConfig struct {
	// Enabled controls whether config sync is active
	Enabled bool
	// Interval is how often to sync pool configs from database
	Interval time.Duration
	// InitialRetryAttempts is how many times to retry initial config sync (matching Java: 12)
	InitialRetryAttempts int
	// InitialRetryDelay is the delay between initial retry attempts (matching Java: 5s)
	InitialRetryDelay time.Duration
	// FailOnInitialSyncError if true, will panic if initial sync fails after all retries
	FailOnInitialSyncError bool
}

// DefaultConfigSyncConfig returns sensible defaults matching Java
func DefaultConfigSyncConfig() *ConfigSyncConfig {
	return &ConfigSyncConfig{
		Enabled:                false,
		Interval:               5 * time.Minute,
		InitialRetryAttempts:   12,                // Java: 12 attempts
		InitialRetryDelay:      5 * time.Second,   // Java: 5s delay
		FailOnInitialSyncError: true,              // Java: fails hard on initial sync
	}
}

// PipelineCleanupConfig holds configuration for stale pipeline entry cleanup
type PipelineCleanupConfig struct {
	// Enabled controls whether cleanup is active
	Enabled bool
	// Interval is how often to run the cleanup
	Interval time.Duration
	// TTL is how long a message can be in the pipeline before being considered stale
	TTL time.Duration
}

// DefaultPipelineCleanupConfig returns sensible defaults
func DefaultPipelineCleanupConfig() *PipelineCleanupConfig {
	return &PipelineCleanupConfig{
		Enabled:  true,
		Interval: 5 * time.Minute,
		TTL:      1 * time.Hour, // Messages older than 1 hour are considered stale
	}
}

// VisibilityExtenderConfig holds configuration for visibility timeout extension
type VisibilityExtenderConfig struct {
	// Enabled controls whether visibility extension is active
	Enabled bool
	// Interval is how often to check for messages needing extension (default 55s)
	Interval time.Duration
	// Threshold is how long a message must be processing before we extend (default 50s)
	Threshold time.Duration
	// ExtensionSeconds is how many seconds to extend visibility (default 120s)
	ExtensionSeconds int32
}

// DefaultVisibilityExtenderConfig returns sensible defaults matching Java implementation
func DefaultVisibilityExtenderConfig() *VisibilityExtenderConfig {
	return &VisibilityExtenderConfig{
		Enabled:          true,
		Interval:         55 * time.Second,  // Run every 55 seconds (like Java)
		Threshold:        50 * time.Second,  // Extend messages processing >50s
		ExtensionSeconds: 120,               // Extend by 120 seconds
	}
}

// ConsumerHealthConfig holds configuration for consumer health monitoring
type ConsumerHealthConfig struct {
	// Enabled controls whether consumer health monitoring is active
	Enabled bool
	// CheckInterval is how often to check consumer health (default 60s)
	CheckInterval time.Duration
	// StallThreshold is how long without activity before considering stalled (default 60s)
	StallThreshold time.Duration
	// MaxRestartAttempts is the maximum restart attempts before giving up (default 3)
	MaxRestartAttempts int
	// RestartDelay is the delay between restart attempts (default 5s)
	RestartDelay time.Duration
}

// DefaultConsumerHealthConfig returns sensible defaults matching Java implementation
func DefaultConsumerHealthConfig() *ConsumerHealthConfig {
	return &ConsumerHealthConfig{
		Enabled:            true,
		CheckInterval:      60 * time.Second,
		StallThreshold:     60 * time.Second,
		MaxRestartAttempts: 3,
		RestartDelay:       5 * time.Second,
	}
}

// LeakDetectionConfig holds configuration for memory leak detection
type LeakDetectionConfig struct {
	// Enabled controls whether leak detection is active
	Enabled bool
	// Interval is how often to check for leaks (matching Java: 30s)
	Interval time.Duration
}

// DefaultLeakDetectionConfig returns sensible defaults matching Java
func DefaultLeakDetectionConfig() *LeakDetectionConfig {
	return &LeakDetectionConfig{
		Enabled:  true,
		Interval: 30 * time.Second, // Java: every 30s
	}
}

// WarningService interface for adding warnings (matches Java's WarningService)
type WarningService interface {
	AddWarning(category, severity, message, source string)
}

// QueueManager manages message routing to processing pools
type QueueManager struct {
	pools          map[string]*pool.ProcessPool
	poolsMu        sync.RWMutex
	drainingPools  sync.Map // map[string]*pool.ProcessPool - pools being drained

	// Dual-ID deduplication (like Java)
	inPipelineMap        sync.Map // pipelineKey (sqsMessageId or appId) -> *DispatchMessage
	inPipelineTimestamps sync.Map // pipelineKey -> int64 (timestamp in millis)
	appIdToPipelineKey   sync.Map // appMessageId -> pipelineKey (for requeue detection)

	mediator       *mediator.HTTPMediator
	messageCallback *MessageCallbackImpl
	running        bool
	runningMu      sync.Mutex
	initialized    bool // Tracks whether initial config sync has completed

	// Standby mode integration (matching Java's StandbyService)
	standbyChecker StandbyChecker

	// Config sync
	poolRepo       *dispatchpool.Repository
	syncConfig     *ConfigSyncConfig
	syncCtx        context.Context
	syncCancel     context.CancelFunc
	syncWg         sync.WaitGroup

	// Pipeline cleanup
	cleanupConfig *PipelineCleanupConfig
	cleanupCtx    context.Context
	cleanupCancel context.CancelFunc
	cleanupWg     sync.WaitGroup

	// Visibility extender (for long-running messages)
	visibilityConfig *VisibilityExtenderConfig
	visibilityCtx    context.Context
	visibilityCancel context.CancelFunc
	visibilityWg     sync.WaitGroup

	// Memory leak detection (matching Java's checkForMapLeaks)
	leakDetectionConfig *LeakDetectionConfig
	leakDetectionCtx    context.Context
	leakDetectionCancel context.CancelFunc
	leakDetectionWg     sync.WaitGroup
	warningService      WarningService
}

// NewQueueManager creates a new queue manager
func NewQueueManager(mediatorCfg *mediator.HTTPMediatorConfig) *QueueManager {
	httpMediator := mediator.NewHTTPMediator(mediatorCfg)

	qm := &QueueManager{
		pools:               make(map[string]*pool.ProcessPool),
		mediator:            httpMediator,
		syncConfig:          DefaultConfigSyncConfig(),
		cleanupConfig:       DefaultPipelineCleanupConfig(),
		visibilityConfig:    DefaultVisibilityExtenderConfig(),
		leakDetectionConfig: DefaultLeakDetectionConfig(),
	}

	// Create message callback that references the manager
	qm.messageCallback = &MessageCallbackImpl{manager: qm}

	return qm
}

// WithVisibilityExtender configures visibility timeout extension for long-running messages
func (m *QueueManager) WithVisibilityExtender(cfg *VisibilityExtenderConfig) *QueueManager {
	if cfg == nil {
		cfg = DefaultVisibilityExtenderConfig()
	}
	m.visibilityConfig = cfg
	return m
}

// WithPipelineCleanup configures stale pipeline entry cleanup
func (m *QueueManager) WithPipelineCleanup(cfg *PipelineCleanupConfig) *QueueManager {
	if cfg == nil {
		cfg = DefaultPipelineCleanupConfig()
	}
	m.cleanupConfig = cfg
	return m
}

// WithConfigSync enables pool configuration sync from database
func (m *QueueManager) WithConfigSync(db *mongo.Database, cfg *ConfigSyncConfig) *QueueManager {
	if cfg == nil {
		cfg = DefaultConfigSyncConfig()
	}
	m.poolRepo = dispatchpool.NewRepository(db)
	m.syncConfig = cfg
	return m
}

// WithStandbyChecker sets the standby checker for HA mode
// When set, config sync will only run if this instance is the primary
func (m *QueueManager) WithStandbyChecker(checker StandbyChecker) *QueueManager {
	m.standbyChecker = checker
	return m
}

// WithLeakDetection configures memory leak detection
func (m *QueueManager) WithLeakDetection(cfg *LeakDetectionConfig) *QueueManager {
	if cfg == nil {
		cfg = DefaultLeakDetectionConfig()
	}
	m.leakDetectionConfig = cfg
	return m
}

// WithWarningService sets the warning service for reporting issues
func (m *QueueManager) WithWarningService(ws WarningService) *QueueManager {
	m.warningService = ws
	return m
}

// Start starts the queue manager
func (m *QueueManager) Start() {
	m.runningMu.Lock()
	defer m.runningMu.Unlock()

	m.running = true

	// Start config sync if enabled
	if m.syncConfig.Enabled && m.poolRepo != nil {
		m.syncCtx, m.syncCancel = context.WithCancel(context.Background())
		m.syncWg.Add(1)
		go m.runConfigSync()
		log.Info().
			Dur("interval", m.syncConfig.Interval).
			Msg("Pool config sync started")
	}

	// Start pipeline cleanup if enabled
	if m.cleanupConfig.Enabled {
		m.cleanupCtx, m.cleanupCancel = context.WithCancel(context.Background())
		m.cleanupWg.Add(1)
		go m.runPipelineCleanup()
		log.Info().
			Dur("interval", m.cleanupConfig.Interval).
			Dur("ttl", m.cleanupConfig.TTL).
			Msg("Pipeline cleanup started")
	}

	// Start visibility extender if enabled (extends SQS visibility for long-running messages)
	if m.visibilityConfig.Enabled {
		m.visibilityCtx, m.visibilityCancel = context.WithCancel(context.Background())
		m.visibilityWg.Add(1)
		go m.runVisibilityExtender()
		log.Info().
			Dur("interval", m.visibilityConfig.Interval).
			Dur("threshold", m.visibilityConfig.Threshold).
			Int32("extensionSeconds", m.visibilityConfig.ExtensionSeconds).
			Msg("Visibility extender started")
	}

	// Start memory leak detection if enabled (matching Java's checkForMapLeaks)
	if m.leakDetectionConfig.Enabled {
		m.leakDetectionCtx, m.leakDetectionCancel = context.WithCancel(context.Background())
		m.leakDetectionWg.Add(1)
		go m.runLeakDetection()
		log.Info().
			Dur("interval", m.leakDetectionConfig.Interval).
			Msg("Memory leak detection started")
	}

	log.Info().Msg("Queue manager started")
}

// Stop stops the queue manager and all pools
func (m *QueueManager) Stop() {
	m.runningMu.Lock()
	m.running = false
	m.runningMu.Unlock()

	// Stop config sync
	if m.syncCancel != nil {
		m.syncCancel()
		m.syncWg.Wait()
	}

	// Stop pipeline cleanup
	if m.cleanupCancel != nil {
		m.cleanupCancel()
		m.cleanupWg.Wait()
	}

	// Stop visibility extender
	if m.visibilityCancel != nil {
		m.visibilityCancel()
		m.visibilityWg.Wait()
	}

	// Stop leak detection
	if m.leakDetectionCancel != nil {
		m.leakDetectionCancel()
		m.leakDetectionWg.Wait()
	}

	m.poolsMu.Lock()
	defer m.poolsMu.Unlock()

	for code, p := range m.pools {
		log.Info().Str("pool", code).Msg("Shutting down pool")
		p.Shutdown()
	}

	log.Info().Msg("Queue manager stopped")
}

// GetOrCreatePool gets or creates a processing pool
func (m *QueueManager) GetOrCreatePool(cfg *PoolConfig) *pool.ProcessPool {
	m.poolsMu.Lock()
	defer m.poolsMu.Unlock()

	if p, exists := m.pools[cfg.Code]; exists {
		return p
	}

	// Create new pool
	p := pool.NewProcessPool(
		cfg.Code,
		cfg.Concurrency,
		cfg.QueueCapacity,
		cfg.RateLimitPerMinute,
		m.mediator,
		m.messageCallback,
	)

	m.pools[cfg.Code] = p
	p.Start()

	log.Info().
		Str("pool", cfg.Code).
		Int("concurrency", cfg.Concurrency).
		Int("queueCapacity", cfg.QueueCapacity).
		Msg("Created new processing pool")

	return p
}

// GetPool gets a pool by code
func (m *QueueManager) GetPool(code string) *pool.ProcessPool {
	m.poolsMu.RLock()
	defer m.poolsMu.RUnlock()
	return m.pools[code]
}

// UpdatePool updates a pool's configuration
func (m *QueueManager) UpdatePool(cfg *PoolConfig) bool {
	m.poolsMu.RLock()
	p, exists := m.pools[cfg.Code]
	m.poolsMu.RUnlock()

	if !exists {
		return false
	}

	// Update concurrency
	if cfg.Concurrency > 0 && cfg.Concurrency != p.GetConcurrency() {
		p.UpdateConcurrency(cfg.Concurrency, 60)
	}

	// Update rate limit
	p.UpdateRateLimit(cfg.RateLimitPerMinute)

	return true
}

// RemovePool removes a pool
func (m *QueueManager) RemovePool(code string) {
	m.poolsMu.Lock()
	defer m.poolsMu.Unlock()

	if p, exists := m.pools[code]; exists {
		p.Drain()
		p.Shutdown()
		delete(m.pools, code)
		log.Info().Str("pool", code).Msg("Removed processing pool")
	}
}

// RouteMessage routes a message to the appropriate pool
func (m *QueueManager) RouteMessage(msg *DispatchMessage) bool {
	m.runningMu.Lock()
	running := m.running
	m.runningMu.Unlock()

	if !running {
		return false
	}

	// Calculate pipeline key for dual-ID tracking
	pipelineKey := msg.SQSMessageID
	if pipelineKey == "" {
		pipelineKey = msg.JobID
	}

	// Check 1: Same SQS message ID (visibility timeout redelivery)
	if msg.SQSMessageID != "" {
		if _, exists := m.inPipelineMap.Load(msg.SQSMessageID); exists {
			log.Debug().
				Str("sqsMessageId", msg.SQSMessageID).
				Str("appMessageId", msg.JobID).
				Msg("Duplicate: visibility timeout redelivery - updating receipt handle")

			// Update the stored message's receipt handle with the new one
			m.updateReceiptHandleIfPossible(msg.SQSMessageID, msg.JobID, msg)

			return true // Already processing
		}
	}

	// Check 2: Same app ID but DIFFERENT SQS ID (external requeue)
	if existingKey, loaded := m.appIdToPipelineKey.Load(msg.JobID); loaded {
		existingSQSID := existingKey.(string)
		if msg.SQSMessageID != "" && msg.SQSMessageID != existingSQSID {
			log.Info().
				Str("appMessageId", msg.JobID).
				Str("existingSQSId", existingSQSID).
				Str("newSQSId", msg.SQSMessageID).
				Msg("Requeued duplicate detected")
			return true // Already processing (caller should ACK to remove)
		}
		// Same app ID with same or empty SQS ID - already in pipeline
		log.Debug().
			Str("messageId", msg.JobID).
			Msg("Duplicate message detected, skipping")
		return true // Already processing
	}

	// Track in pipeline maps
	m.inPipelineMap.Store(pipelineKey, msg)
	m.inPipelineTimestamps.Store(pipelineKey, time.Now().UnixMilli())
	m.appIdToPipelineKey.Store(msg.JobID, pipelineKey)

	// Get or create pool (defaults match Java)
	poolCfg := &PoolConfig{
		Code:          msg.DispatchPoolID,
		Concurrency:   DefaultPoolConcurrency,
		QueueCapacity: max(DefaultPoolConcurrency*DefaultQueueCapacityMultiplier, MinQueueCapacity),
	}

	p := m.GetOrCreatePool(poolCfg)

	// Convert to MessagePointer with callback functions
	pointer := &pool.MessagePointer{
		ID:              msg.JobID,
		SQSMessageID:    msg.SQSMessageID,
		BatchID:         msg.BatchID,
		MessageGroupID:  msg.MessageGroup,
		MediationTarget: msg.TargetURL,
		MediationType:   msg.MediationType,
		AuthToken:       msg.AuthToken, // For Bearer auth to processing endpoint
		Payload:         []byte(msg.Payload),
		Headers:         msg.Headers,
		TimeoutSeconds:  msg.TimeoutSeconds,
		AckFunc:         msg.AckFunc,
		NakFunc:         msg.NakFunc,
		NakDelayFunc:    msg.NakDelayFunc,
		InProgressFunc:  msg.InProgressFunc,
	}

	// Submit to pool
	if !p.Submit(pointer) {
		// Pool rejected message - remove from pipeline maps
		m.cleanupPipelineEntry(msg.JobID, pipelineKey)
		return false
	}

	return true
}

// RouteMessageBatch routes a batch of messages with deduplication and failure barriers
// This implements the 3-phase processing from the Java version:
// 1. Deduplication - skip messages already in pipeline
// 2. Capacity & Rate Limit Check - check pool availability
// 3. FIFO with Failure Barrier - nack remaining messages in group on failure
func (m *QueueManager) RouteMessageBatch(ctx context.Context, messages []*DispatchMessage) BatchRouteResult {
	result := BatchRouteResult{
		Submitted:    0,
		Deduplicated: 0,
		Rejected:     0,
		FailBarrier:  0,
	}

	if len(messages) == 0 {
		return result
	}

	m.runningMu.Lock()
	running := m.running
	m.runningMu.Unlock()

	if !running {
		// Nack all messages if not running
		for _, msg := range messages {
			if msg.NakFunc != nil {
				msg.NakFunc()
			}
		}
		result.Rejected = len(messages)
		return result
	}

	// Phase 1: Dual-ID Deduplication (like Java)
	// Tracks both SQS message ID and App message ID to detect:
	// 1. Visibility timeout redelivery (same SQS ID) - NACK to let SQS retry later
	// 2. External requeue (same App ID, different SQS ID) - ACK to remove duplicate
	dedupedMessages := make([]*DispatchMessage, 0, len(messages))
	var visibilityTimeoutDups []*DispatchMessage
	var requeuedDups []*DispatchMessage

	for _, msg := range messages {
		sqsMessageId := msg.SQSMessageID
		appMessageId := msg.JobID

		// Check 1: Same SQS message ID (visibility timeout redelivery)
		if sqsMessageId != "" {
			if _, exists := m.inPipelineMap.Load(sqsMessageId); exists {
				log.Debug().
					Str("sqsMessageId", sqsMessageId).
					Str("appMessageId", appMessageId).
					Msg("Duplicate: visibility timeout redelivery - updating receipt handle and NACK")

				// Update the stored message's receipt handle with the new one from the redelivered message
				// This ensures when processing completes, ACK uses the valid (latest) receipt handle
				m.updateReceiptHandleIfPossible(sqsMessageId, appMessageId, msg)

				visibilityTimeoutDups = append(visibilityTimeoutDups, msg)
				result.Deduplicated++
				continue
			}
		}

		// Check 2: Same app ID but DIFFERENT SQS ID (external requeue)
		if existingKey, loaded := m.appIdToPipelineKey.Load(appMessageId); loaded {
			existingSQSID := existingKey.(string)
			if sqsMessageId != "" && sqsMessageId != existingSQSID {
				log.Info().
					Str("appMessageId", appMessageId).
					Str("existingSQSId", existingSQSID).
					Str("newSQSId", sqsMessageId).
					Msg("Requeued duplicate detected - will ACK to remove")
				requeuedDups = append(requeuedDups, msg)
				result.Deduplicated++
				continue
			}
		}

		dedupedMessages = append(dedupedMessages, msg)
	}

	// NACK visibility timeout redeliveries (let SQS retry later)
	for _, dup := range visibilityTimeoutDups {
		if dup.NakFunc != nil {
			dup.NakFunc()
		}
	}

	// ACK requeued duplicates (permanently remove from queue)
	for _, dup := range requeuedDups {
		if dup.AckFunc != nil {
			dup.AckFunc()
		}
	}

	if len(dedupedMessages) == 0 {
		return result
	}

	// Phase 2: Group by pool and check capacity
	messagesByPool := make(map[string][]*DispatchMessage)
	for _, msg := range dedupedMessages {
		poolCode := msg.DispatchPoolID
		if poolCode == "" {
			poolCode = "default"
		}
		messagesByPool[poolCode] = append(messagesByPool[poolCode], msg)
	}

	// Check capacity for each pool
	poolsWithCapacity := make(map[string]bool)
	for poolCode, poolMessages := range messagesByPool {
		p := m.GetPool(poolCode)
		if p != nil {
			// Check rate limiting and capacity
			if p.IsRateLimited() {
				log.Warn().
					Str("pool", poolCode).
					Int("messageCount", len(poolMessages)).
					Msg("Pool rate limited, nacking batch for pool")
				for _, msg := range poolMessages {
					m.inPipelineMap.Delete(msg.JobID)
					if msg.NakFunc != nil {
						msg.NakFunc()
					}
				}
				result.Rejected += len(poolMessages)
				continue
			}
			if !p.HasCapacity(len(poolMessages)) {
				log.Warn().
					Str("pool", poolCode).
					Int("messageCount", len(poolMessages)).
					Msg("Pool at capacity, nacking batch for pool")
				for _, msg := range poolMessages {
					m.inPipelineMap.Delete(msg.JobID)
					if msg.NakFunc != nil {
						msg.NakFunc()
					}
				}
				result.Rejected += len(poolMessages)
				continue
			}
		}
		poolsWithCapacity[poolCode] = true
	}

	// Phase 3: FIFO with Failure Barrier per message group
	for poolCode, poolMessages := range messagesByPool {
		if !poolsWithCapacity[poolCode] {
			continue // Already rejected
		}

		// Get or create pool (defaults match Java)
		poolCfg := &PoolConfig{
			Code:          poolCode,
			Concurrency:   DefaultPoolConcurrency,
			QueueCapacity: max(DefaultPoolConcurrency*DefaultQueueCapacityMultiplier, MinQueueCapacity),
		}
		p := m.GetOrCreatePool(poolCfg)

		// Group by messageGroupId (preserve order using slice)
		type groupEntry struct {
			groupID  string
			messages []*DispatchMessage
		}
		messagesByGroup := make([]groupEntry, 0)
		groupIndex := make(map[string]int)

		for _, msg := range poolMessages {
			groupID := msg.MessageGroup
			if groupID == "" {
				groupID = "__DEFAULT__"
			}

			if idx, exists := groupIndex[groupID]; exists {
				messagesByGroup[idx].messages = append(messagesByGroup[idx].messages, msg)
			} else {
				groupIndex[groupID] = len(messagesByGroup)
				messagesByGroup = append(messagesByGroup, groupEntry{
					groupID:  groupID,
					messages: []*DispatchMessage{msg},
				})
			}
		}

		// Process each group sequentially with failure barrier
		for _, group := range messagesByGroup {
			nackRemaining := false

			for _, msg := range group.messages {
				// Calculate pipeline key for dual-ID tracking
				pipelineKey := msg.SQSMessageID
				if pipelineKey == "" {
					pipelineKey = msg.JobID
				}

				if nackRemaining {
					// Failure barrier: nack remaining messages in this group
					m.cleanupPipelineEntry(msg.JobID, pipelineKey)
					if msg.NakFunc != nil {
						msg.NakFunc()
					}
					result.FailBarrier++
					continue
				}

				// Track in pipeline maps BEFORE submitting
				m.inPipelineMap.Store(pipelineKey, msg)
				m.inPipelineTimestamps.Store(pipelineKey, time.Now().UnixMilli())
				m.appIdToPipelineKey.Store(msg.JobID, pipelineKey)

				// Convert to MessagePointer
				pointer := &pool.MessagePointer{
					ID:              msg.JobID,
					SQSMessageID:    msg.SQSMessageID,
					BatchID:         msg.BatchID,
					MessageGroupID:  msg.MessageGroup,
					MediationTarget: msg.TargetURL,
					MediationType:   msg.MediationType,
					AuthToken:       msg.AuthToken, // For Bearer auth to processing endpoint
					Payload:         []byte(msg.Payload),
					Headers:         msg.Headers,
					TimeoutSeconds:  msg.TimeoutSeconds,
					AckFunc:         msg.AckFunc,
					NakFunc:         msg.NakFunc,
					NakDelayFunc:    msg.NakDelayFunc,
					InProgressFunc:  msg.InProgressFunc,
				}

				// Submit to pool
				if !p.Submit(pointer) {
					// Submit failed - activate failure barrier for this group
					log.Warn().
						Str("pool", poolCode).
						Str("messageId", msg.JobID).
						Str("group", group.groupID).
						Msg("Failed to submit message, activating failure barrier")
					m.cleanupPipelineEntry(msg.JobID, pipelineKey)
					if msg.NakFunc != nil {
						msg.NakFunc()
					}
					nackRemaining = true
					result.Rejected++
				} else {
					result.Submitted++
				}
			}
		}
	}

	log.Info().
		Int("submitted", result.Submitted).
		Int("deduplicated", result.Deduplicated).
		Int("rejected", result.Rejected).
		Int("failBarrier", result.FailBarrier).
		Msg("Batch routing complete")

	return result
}

// BatchRouteResult contains the results of batch routing
type BatchRouteResult struct {
	Submitted    int // Successfully submitted to pools
	Deduplicated int // Skipped as duplicates
	Rejected     int // Rejected due to capacity/rate limiting
	FailBarrier  int // Nacked due to failure barrier
}

// cleanupPipelineEntry removes a message from all pipeline tracking maps
func (m *QueueManager) cleanupPipelineEntry(appMessageId, pipelineKey string) {
	m.inPipelineMap.Delete(pipelineKey)
	m.inPipelineTimestamps.Delete(pipelineKey)
	m.appIdToPipelineKey.Delete(appMessageId)
}

// updateReceiptHandleIfPossible updates the receipt handle on the stored message
// when a redelivery is detected. This mirrors Java's QueueManager.updateReceiptHandleIfPossible.
// Called when SQS redelivers a message due to visibility timeout expiring while
// the original message is still being processed.
func (m *QueueManager) updateReceiptHandleIfPossible(pipelineKey, appMessageId string, newMsg *DispatchMessage) {
	// Get stored message from pipeline
	storedValue, exists := m.inPipelineMap.Load(pipelineKey)
	if !exists {
		log.Warn().
			Str("pipelineKey", pipelineKey).
			Str("appMessageId", appMessageId).
			Msg("Cannot update receipt handle - no stored message found")
		return
	}

	storedMsg, ok := storedValue.(*DispatchMessage)
	if !ok {
		log.Warn().
			Str("pipelineKey", pipelineKey).
			Msg("Cannot update receipt handle - stored value is not DispatchMessage")
		return
	}

	// Check if stored message supports receipt handle updates
	if storedMsg.UpdateReceiptHandleFunc == nil {
		log.Debug().
			Str("appMessageId", appMessageId).
			Msg("Stored message does not support receipt handle updates")
		return
	}

	// Check if new message can provide its receipt handle
	if newMsg.GetReceiptHandleFunc == nil {
		log.Warn().
			Str("appMessageId", appMessageId).
			Msg("New message cannot provide receipt handle for update")
		return
	}

	// Get the new receipt handle and update the stored message
	newReceiptHandle := newMsg.GetReceiptHandleFunc()
	if newReceiptHandle == "" {
		log.Warn().
			Str("appMessageId", appMessageId).
			Msg("New receipt handle is empty - cannot update")
		return
	}

	// Get old handle for logging (if available)
	oldReceiptHandle := ""
	if storedMsg.GetReceiptHandleFunc != nil {
		oldReceiptHandle = storedMsg.GetReceiptHandleFunc()
	}

	// Update the stored message's receipt handle
	storedMsg.UpdateReceiptHandleFunc(newReceiptHandle)

	log.Info().
		Str("appMessageId", appMessageId).
		Str("pipelineKey", pipelineKey).
		Str("oldHandle", truncateHandle(oldReceiptHandle)).
		Str("newHandle", truncateHandle(newReceiptHandle)).
		Msg("Updated receipt handle for in-pipeline message due to redelivery")
}

// truncateHandle truncates a receipt handle for logging (first 20 chars)
func truncateHandle(handle string) string {
	if len(handle) <= 20 {
		return handle
	}
	return handle[:20] + "..."
}

// cleanupPipelineEntryFromPointer removes a message from all pipeline tracking maps using MessagePointer
func (m *QueueManager) cleanupPipelineEntryFromPointer(msg *pool.MessagePointer) {
	pipelineKey := msg.SQSMessageID
	if pipelineKey == "" {
		pipelineKey = msg.ID
	}
	m.cleanupPipelineEntry(msg.ID, pipelineKey)
}

// Ack acknowledges a message
func (m *QueueManager) Ack(msg *pool.MessagePointer) {
	m.cleanupPipelineEntryFromPointer(msg)
	if msg.AckFunc != nil {
		if err := msg.AckFunc(); err != nil {
			log.Error().Err(err).Str("messageId", msg.ID).Msg("Failed to ack message")
		}
	}
}

// Nack negative-acknowledges a message
func (m *QueueManager) Nack(msg *pool.MessagePointer) {
	m.cleanupPipelineEntryFromPointer(msg)
	if msg.NakFunc != nil {
		if err := msg.NakFunc(); err != nil {
			log.Error().Err(err).Str("messageId", msg.ID).Msg("Failed to nack message")
		}
	}
}

// MessageCallbackImpl implements pool.MessageCallback
type MessageCallbackImpl struct {
	manager *QueueManager
}

func (c *MessageCallbackImpl) Ack(msg *pool.MessagePointer) {
	c.manager.Ack(msg)
}

func (c *MessageCallbackImpl) Nack(msg *pool.MessagePointer) {
	c.manager.Nack(msg)
}

func (c *MessageCallbackImpl) SetVisibilityDelay(msg *pool.MessagePointer, seconds int) {
	if msg.NakDelayFunc != nil {
		msg.NakDelayFunc(time.Duration(seconds) * time.Second)
	}
}

func (c *MessageCallbackImpl) SetFastFailVisibility(msg *pool.MessagePointer) {
	// Fast fail = 1 second visibility for quick retry
	c.SetVisibilityDelay(msg, 1)
}

func (c *MessageCallbackImpl) ResetVisibilityToDefault(msg *pool.MessagePointer) {
	// Default visibility handled by queue implementation
}

// DispatchMessage represents a message for dispatch
// This is the internal representation used for pipeline tracking and routing.
// It is populated from model.MessagePointer when consuming from the queue.
type DispatchMessage struct {
	JobID          string            `json:"jobId"`
	SQSMessageID   string            `json:"-"` // Broker message ID for deduplication (not serialized)
	DispatchPoolID string            `json:"dispatchPoolId"`
	MessageGroup   string            `json:"messageGroup"`
	BatchID        string            `json:"batchId"`
	Sequence       int               `json:"sequence"`
	TargetURL      string            `json:"targetUrl"`
	Headers        map[string]string `json:"headers,omitempty"`
	Payload        string            `json:"payload"`
	ContentType    string            `json:"contentType"`
	TimeoutSeconds int               `json:"timeoutSeconds"`
	MaxRetries     int               `json:"maxRetries"`
	AttemptNumber  int               `json:"attemptNumber"`

	// Fields from MessagePointer for Java compatibility
	AuthToken     string `json:"-"` // HMAC auth token for Bearer auth
	MediationType string `json:"-"` // Mediation type (HTTP)

	// Callback functions for queue integration
	AckFunc        func() error              `json:"-"`
	NakFunc        func() error              `json:"-"`
	NakDelayFunc   func(time.Duration) error `json:"-"`
	InProgressFunc func() error              `json:"-"`

	// Receipt handle management for SQS redelivery handling
	// When a message is redelivered due to visibility timeout, we need to
	// update the stored message's receipt handle to use the new (valid) one
	UpdateReceiptHandleFunc func(string)  `json:"-"` // Updates receipt handle on redelivery
	GetReceiptHandleFunc    func() string `json:"-"` // Gets current receipt handle
}

// Consumer consumes messages from the queue and routes them
type Consumer struct {
	manager   *QueueManager
	consumer  queue.Consumer
	ctx       context.Context
	cancel    context.CancelFunc
	wg        sync.WaitGroup

	// Health monitoring
	lastActivity   atomic.Int64 // Unix timestamp of last activity (poll or message)
	restartCount   int          // Number of restart attempts
	restartCountMu sync.Mutex
	stalled        atomic.Bool  // Whether consumer is considered stalled
}

// NewConsumer creates a new consumer
func NewConsumer(manager *QueueManager, queueConsumer queue.Consumer) *Consumer {
	ctx, cancel := context.WithCancel(context.Background())
	c := &Consumer{
		manager:  manager,
		consumer: queueConsumer,
		ctx:      ctx,
		cancel:   cancel,
	}
	// Initialize last activity to now
	c.lastActivity.Store(time.Now().Unix())
	return c
}

// updateActivity updates the last activity timestamp
func (c *Consumer) updateActivity() {
	c.lastActivity.Store(time.Now().Unix())
}

// GetLastActivity returns the last activity timestamp
func (c *Consumer) GetLastActivity() time.Time {
	return time.Unix(c.lastActivity.Load(), 0)
}

// IsStalled returns whether the consumer is considered stalled
func (c *Consumer) IsStalled() bool {
	return c.stalled.Load()
}

// GetRestartCount returns the number of restart attempts
func (c *Consumer) GetRestartCount() int {
	c.restartCountMu.Lock()
	defer c.restartCountMu.Unlock()
	return c.restartCount
}

// incrementRestartCount increments and returns the new restart count
func (c *Consumer) incrementRestartCount() int {
	c.restartCountMu.Lock()
	defer c.restartCountMu.Unlock()
	c.restartCount++
	return c.restartCount
}

// resetRestartCount resets the restart count to zero
func (c *Consumer) resetRestartCount() {
	c.restartCountMu.Lock()
	defer c.restartCountMu.Unlock()
	c.restartCount = 0
}

// Start starts consuming messages
func (c *Consumer) Start() {
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		c.consume()
	}()
	log.Info().Msg("Consumer started")
}

// Stop stops the consumer
func (c *Consumer) Stop() {
	c.cancel()
	c.wg.Wait()
	log.Info().Msg("Consumer stopped")
}

// WireReceiptHandleCallbacks sets up receipt handle callbacks on a DispatchMessage
// from a queue.Message. This enables receipt handle updates when SQS redelivers
// a message due to visibility timeout while the original is still being processed.
func WireReceiptHandleCallbacks(dispatchMsg *DispatchMessage, queueMsg queue.Message) {
	// Check if the queue message supports receipt handle updates (SQS-specific)
	if updatable, ok := queueMsg.(queue.ReceiptHandleUpdatable); ok {
		dispatchMsg.UpdateReceiptHandleFunc = updatable.UpdateReceiptHandle
		dispatchMsg.GetReceiptHandleFunc = updatable.GetReceiptHandle
	}
}

// consume processes messages from the queue
func (c *Consumer) consume() {
	err := c.consumer.Consume(c.ctx, func(msg queue.Message) error {
		// Update activity timestamp on every message received
		c.updateActivity()

		// Parse MessagePointer (Java-compatible format)
		var pointer model.MessagePointer
		if err := json.Unmarshal(msg.Data(), &pointer); err != nil {
			log.Error().Err(err).Msg("Failed to unmarshal MessagePointer")
			// Ack to prevent infinite retry of malformed messages
			msg.Ack()
			return nil
		}

		// Convert MessagePointer to internal DispatchMessage for pipeline tracking
		dispatchMsg := DispatchMessage{
			JobID:          pointer.ID,
			SQSMessageID:   msg.ID(), // Broker message ID for dual-ID deduplication
			DispatchPoolID: pointer.PoolCode,
			MessageGroup:   pointer.MessageGroupID,
			TargetURL:      pointer.MediationTarget,
			// These fields are populated from MessagePointer for mediator use
			AuthToken:      pointer.AuthToken,
			MediationType:  string(pointer.MediationType),
		}

		// Set up ack/nack callback functions
		dispatchMsg.AckFunc = msg.Ack
		dispatchMsg.NakFunc = msg.Nak
		dispatchMsg.NakDelayFunc = msg.NakWithDelay
		dispatchMsg.InProgressFunc = msg.InProgress

		// Wire up receipt handle callbacks for SQS redelivery handling
		WireReceiptHandleCallbacks(&dispatchMsg, msg)

		// Use RouteMessage for proper deduplication and pipeline tracking
		if !c.manager.RouteMessage(&dispatchMsg) {
			// Pool rejected - nack for redelivery
			log.Warn().
				Str("messageId", dispatchMsg.JobID).
				Str("pool", dispatchMsg.DispatchPoolID).
				Msg("Pool rejected message, nacking for redelivery")
			msg.Nak()
		}

		return nil
	})

	if err != nil && err != context.Canceled {
		log.Error().Err(err).Msg("Consumer error")
	}
}

// ConsumerFactory creates new queue consumers for restart
type ConsumerFactory func() queue.Consumer

// Router ties together all message router components
type Router struct {
	manager         *QueueManager
	consumer        *Consumer
	consumerMu      sync.Mutex
	consumerFactory ConsumerFactory

	// Health monitoring
	healthConfig   *ConsumerHealthConfig
	healthCtx      context.Context
	healthCancel   context.CancelFunc
	healthWg       sync.WaitGroup
}

// NewRouter creates a new message router
func NewRouter(queueConsumer queue.Consumer, mediatorCfg *mediator.HTTPMediatorConfig) *Router {
	manager := NewQueueManager(mediatorCfg)

	var consumer *Consumer
	if queueConsumer != nil {
		consumer = NewConsumer(manager, queueConsumer)
	}

	return &Router{
		manager:      manager,
		consumer:     consumer,
		healthConfig: DefaultConsumerHealthConfig(),
	}
}

// WithConsumerFactory sets a factory for creating new consumers on restart
func (r *Router) WithConsumerFactory(factory ConsumerFactory) *Router {
	r.consumerFactory = factory
	return r
}

// WithConsumerHealthConfig configures consumer health monitoring
func (r *Router) WithConsumerHealthConfig(cfg *ConsumerHealthConfig) *Router {
	if cfg == nil {
		cfg = DefaultConsumerHealthConfig()
	}
	r.healthConfig = cfg
	return r
}

// Start starts the router
func (r *Router) Start() {
	r.manager.Start()
	if r.consumer != nil {
		r.consumer.Start()
	}

	// Start consumer health monitor if enabled
	if r.healthConfig.Enabled && r.consumer != nil {
		r.healthCtx, r.healthCancel = context.WithCancel(context.Background())
		r.healthWg.Add(1)
		go r.runConsumerHealthMonitor()
		log.Info().
			Dur("checkInterval", r.healthConfig.CheckInterval).
			Dur("stallThreshold", r.healthConfig.StallThreshold).
			Int("maxRestarts", r.healthConfig.MaxRestartAttempts).
			Msg("Consumer health monitor started")
	}

	log.Info().Msg("Message router started")
}

// Stop stops the router
func (r *Router) Stop() {
	// Stop health monitor first
	if r.healthCancel != nil {
		r.healthCancel()
		r.healthWg.Wait()
	}

	r.consumerMu.Lock()
	consumer := r.consumer
	r.consumerMu.Unlock()

	if consumer != nil {
		consumer.Stop()
	}
	r.manager.Stop()
	log.Info().Msg("Message router stopped")
}

// Manager returns the queue manager
func (r *Router) Manager() *QueueManager {
	return r.manager
}

// Consumer returns the current consumer (for health checks)
func (r *Router) Consumer() *Consumer {
	r.consumerMu.Lock()
	defer r.consumerMu.Unlock()
	return r.consumer
}

// runConsumerHealthMonitor monitors consumer health and auto-restarts if stalled
func (r *Router) runConsumerHealthMonitor() {
	defer r.healthWg.Done()

	ticker := time.NewTicker(r.healthConfig.CheckInterval)
	defer ticker.Stop()

	for {
		select {
		case <-r.healthCtx.Done():
			log.Info().Msg("Consumer health monitor stopped")
			return
		case <-ticker.C:
			r.checkConsumerHealth()
		}
	}
}

// checkConsumerHealth checks if the consumer is stalled and restarts if needed
func (r *Router) checkConsumerHealth() {
	r.consumerMu.Lock()
	consumer := r.consumer
	r.consumerMu.Unlock()

	if consumer == nil {
		return
	}

	// Check if consumer has been inactive for too long
	lastActivity := consumer.GetLastActivity()
	stalledDuration := time.Since(lastActivity)

	if stalledDuration < r.healthConfig.StallThreshold {
		// Consumer is healthy - reset stalled flag and restart count
		if consumer.IsStalled() {
			consumer.stalled.Store(false)
			consumer.resetRestartCount()
			log.Info().Msg("Consumer recovered from stalled state")
		}
		return
	}

	// Consumer appears stalled
	consumer.stalled.Store(true)
	restartCount := consumer.GetRestartCount()

	// Record stall event metric
	metrics.ConsumerStallEvents.Inc()

	log.Warn().
		Dur("stalledFor", stalledDuration).
		Int("restartAttempts", restartCount).
		Int("maxAttempts", r.healthConfig.MaxRestartAttempts).
		Msg("Consumer appears stalled")

	// Check if we've exceeded max restart attempts
	if restartCount >= r.healthConfig.MaxRestartAttempts {
		log.Error().
			Int("attempts", restartCount).
			Msg("Consumer exceeded max restart attempts - requires manual intervention")
		return
	}

	// Attempt restart
	r.restartConsumer()
}

// restartConsumer stops the current consumer and creates a new one
func (r *Router) restartConsumer() {
	r.consumerMu.Lock()
	defer r.consumerMu.Unlock()

	oldConsumer := r.consumer
	if oldConsumer == nil {
		return
	}

	attempt := oldConsumer.incrementRestartCount()

	// Record restart attempt metric
	metrics.ConsumerRestarts.Inc()

	log.Info().
		Int("attempt", attempt).
		Int("maxAttempts", r.healthConfig.MaxRestartAttempts).
		Msg("Restarting stalled consumer")

	// Stop old consumer
	oldConsumer.Stop()

	// Wait between restart attempts
	time.Sleep(r.healthConfig.RestartDelay)

	// Create new consumer if we have a factory
	if r.consumerFactory != nil {
		newQueueConsumer := r.consumerFactory()
		if newQueueConsumer != nil {
			newConsumer := NewConsumer(r.manager, newQueueConsumer)
			// Preserve restart count from old consumer
			newConsumer.restartCount = attempt
			newConsumer.Start()
			r.consumer = newConsumer

			log.Info().
				Int("attempt", attempt).
				Msg("Consumer restarted successfully")
			return
		}
	}

	// No factory or factory returned nil - try to restart with existing queue consumer
	// This is a fallback that may not work if the underlying connection is broken
	log.Warn().Msg("No consumer factory available, attempting restart with existing consumer")
	newConsumer := NewConsumer(r.manager, oldConsumer.consumer)
	newConsumer.restartCount = attempt
	newConsumer.Start()
	r.consumer = newConsumer
}

// GenerateBatchID generates a new batch ID
func GenerateBatchID() string {
	return tsid.Generate()
}

// runConfigSync runs the pool configuration sync loop
func (m *QueueManager) runConfigSync() {
	defer m.syncWg.Done()

	// Do initial sync with retry logic (matching Java: 12 attempts × 5s)
	if !m.doInitialSyncWithRetry() {
		if m.syncConfig.FailOnInitialSyncError {
			log.Fatal().Msg("Initial pool config sync failed after all retries - shutting down")
		}
		log.Error().Msg("Initial pool config sync failed - continuing with empty config")
	}

	ticker := time.NewTicker(m.syncConfig.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-m.syncCtx.Done():
			log.Info().Msg("Pool config sync stopped")
			return
		case <-ticker.C:
			m.syncPoolConfig()
		}
	}
}

// doInitialSyncWithRetry performs initial config sync with retry logic
// Matches Java's 12 attempts × 5s delay pattern
func (m *QueueManager) doInitialSyncWithRetry() bool {
	maxAttempts := m.syncConfig.InitialRetryAttempts
	if maxAttempts <= 0 {
		maxAttempts = 1
	}

	for attempt := 1; attempt <= maxAttempts; attempt++ {
		// Check standby status before syncing (matching Java)
		if m.standbyChecker != nil && !m.standbyChecker.IsPrimary() {
			log.Info().
				Int("attempt", attempt).
				Msg("In standby mode, waiting for primary lock before initial sync...")
			time.Sleep(m.syncConfig.InitialRetryDelay)
			continue
		}

		if m.syncPoolConfigWithResult() {
			m.initialized = true
			log.Info().
				Int("attempt", attempt).
				Msg("Initial pool config sync completed successfully")
			return true
		}

		if attempt < maxAttempts {
			log.Warn().
				Int("attempt", attempt).
				Int("maxAttempts", maxAttempts).
				Dur("retryDelay", m.syncConfig.InitialRetryDelay).
				Msg("Initial pool config sync failed, retrying...")
			time.Sleep(m.syncConfig.InitialRetryDelay)
		}
	}

	log.Error().
		Int("attempts", maxAttempts).
		Msg("Initial pool config sync failed after all retry attempts")
	return false
}

// syncPoolConfig syncs pool configurations from the database
func (m *QueueManager) syncPoolConfig() {
	// Check standby status before syncing (matching Java)
	if m.standbyChecker != nil && !m.standbyChecker.IsPrimary() {
		if !m.initialized {
			log.Info().Msg("In standby mode, waiting for primary lock...")
			m.initialized = true // Only log once
		}
		return
	}

	m.syncPoolConfigWithResult()
}

// syncPoolConfigWithResult syncs pool configs and returns success/failure
func (m *QueueManager) syncPoolConfigWithResult() bool {
	ctx, cancel := context.WithTimeout(m.syncCtx, 30*time.Second)
	defer cancel()

	configs, err := m.poolRepo.FindAllEnabled(ctx)
	if err != nil {
		log.Error().Err(err).Msg("Failed to fetch pool configs from database")
		return false
	}

	// Track which pools we've seen in this sync
	activeCodes := make(map[string]bool)

	for _, cfg := range configs {
		activeCodes[cfg.Code] = true

		m.poolsMu.RLock()
		existing, exists := m.pools[cfg.Code]
		m.poolsMu.RUnlock()

		if exists {
			// Update existing pool configuration
			updated := false

			// Update concurrency if changed
			if cfg.Concurrency > 0 && cfg.Concurrency != existing.GetConcurrency() {
				existing.UpdateConcurrency(cfg.Concurrency, 60)
				updated = true
			}

			// Update rate limit if changed
			existing.UpdateRateLimit(cfg.RateLimitPerMin)

			if updated {
				log.Debug().
					Str("pool", cfg.Code).
					Int("concurrency", cfg.Concurrency).
					Msg("Updated pool configuration")
			}
		} else {
			// Create new pool from database config (defaults match Java)
			poolCfg := &PoolConfig{
				Code:               cfg.Code,
				Concurrency:        cfg.GetConcurrencyOrDefault(DefaultPoolConcurrency),
				QueueCapacity:      cfg.GetQueueCapacityOrDefault(DefaultPoolConcurrency * DefaultQueueCapacityMultiplier),
				RateLimitPerMinute: cfg.RateLimitPerMin,
			}

			m.GetOrCreatePool(poolCfg)
			log.Info().
				Str("pool", cfg.Code).
				Int("concurrency", poolCfg.Concurrency).
				Int("queueCapacity", poolCfg.QueueCapacity).
				Msg("Created pool from database config")
		}
	}

	// Find pools that are no longer in the database (disabled or deleted)
	m.poolsMu.RLock()
	poolsToRemove := make([]string, 0)
	for code := range m.pools {
		if !activeCodes[code] && code != "default" {
			poolsToRemove = append(poolsToRemove, code)
		}
	}
	m.poolsMu.RUnlock()

	// Drain and remove pools that are no longer active
	for _, code := range poolsToRemove {
		m.drainPool(code)
	}

	if len(configs) > 0 || len(poolsToRemove) > 0 {
		log.Debug().
			Int("activeCount", len(configs)).
			Int("removedCount", len(poolsToRemove)).
			Msg("Pool config sync completed")
	}

	return true
}

// drainPool gracefully drains and removes a pool
func (m *QueueManager) drainPool(code string) {
	m.poolsMu.Lock()
	p, exists := m.pools[code]
	if !exists {
		m.poolsMu.Unlock()
		return
	}
	delete(m.pools, code)
	m.poolsMu.Unlock()

	// Store in draining map for tracking
	m.drainingPools.Store(code, p)

	log.Info().Str("pool", code).Msg("Draining pool (no longer in database)")

	// Async drain and shutdown
	go func() {
		p.Drain()
		p.Shutdown()
		m.drainingPools.Delete(code)
		log.Info().Str("pool", code).Msg("Pool drained and removed")
	}()
}

// runPipelineCleanup runs the stale pipeline entry cleanup loop
// This removes entries from inPipelineMap and related maps for messages
// that have been in the pipeline longer than the configured TTL
func (m *QueueManager) runPipelineCleanup() {
	defer m.cleanupWg.Done()

	ticker := time.NewTicker(m.cleanupConfig.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-m.cleanupCtx.Done():
			log.Info().Msg("Pipeline cleanup stopped")
			return
		case <-ticker.C:
			m.cleanupStalePipelineEntries()
		}
	}
}

// cleanupStalePipelineEntries removes stale entries from pipeline tracking maps
// This prevents memory leaks from messages that got stuck in the pipeline
func (m *QueueManager) cleanupStalePipelineEntries() {
	now := time.Now().UnixMilli()
	ttlMillis := m.cleanupConfig.TTL.Milliseconds()
	cleanedCount := 0

	// Collect stale entries
	var staleKeys []string
	var staleAppIds []string

	m.inPipelineTimestamps.Range(func(key, value interface{}) bool {
		pipelineKey := key.(string)
		timestamp := value.(int64)

		if now-timestamp > ttlMillis {
			staleKeys = append(staleKeys, pipelineKey)

			// Also track the app ID for cleanup
			if msgValue, exists := m.inPipelineMap.Load(pipelineKey); exists {
				if msg, ok := msgValue.(*DispatchMessage); ok {
					staleAppIds = append(staleAppIds, msg.JobID)
				}
			}
		}
		return true
	})

	// Remove stale entries
	for i, pipelineKey := range staleKeys {
		m.inPipelineMap.Delete(pipelineKey)
		m.inPipelineTimestamps.Delete(pipelineKey)
		if i < len(staleAppIds) {
			m.appIdToPipelineKey.Delete(staleAppIds[i])
		}
		cleanedCount++
	}

	if cleanedCount > 0 {
		log.Warn().
			Int("count", cleanedCount).
			Dur("ttl", m.cleanupConfig.TTL).
			Msg("Cleaned up stale pipeline entries - messages may have been stuck")
	}
}

// runVisibilityExtender runs the visibility extension loop
// This extends SQS visibility timeout for long-running messages to prevent
// them from timing out and being redelivered while still processing.
// Runs every 55 seconds (like Java's SqsQueueConsumer) and extends messages
// that have been processing for >50 seconds.
func (m *QueueManager) runVisibilityExtender() {
	defer m.visibilityWg.Done()

	ticker := time.NewTicker(m.visibilityConfig.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-m.visibilityCtx.Done():
			log.Info().Msg("Visibility extender stopped")
			return
		case <-ticker.C:
			m.extendLongRunningVisibility()
		}
	}
}

// extendLongRunningVisibility extends visibility for messages processing longer than threshold
func (m *QueueManager) extendLongRunningVisibility() {
	now := time.Now().UnixMilli()
	thresholdMillis := m.visibilityConfig.Threshold.Milliseconds()
	extendedCount := 0

	m.inPipelineTimestamps.Range(func(key, value interface{}) bool {
		pipelineKey := key.(string)
		startTime := value.(int64)
		elapsedMillis := now - startTime

		// Only extend if message has been processing longer than threshold
		if elapsedMillis < thresholdMillis {
			return true
		}

		// Get the message from the pipeline
		msgValue, exists := m.inPipelineMap.Load(pipelineKey)
		if !exists {
			return true
		}

		msg, ok := msgValue.(*DispatchMessage)
		if !ok || msg.InProgressFunc == nil {
			return true
		}

		// Extend visibility by calling InProgress
		if err := msg.InProgressFunc(); err != nil {
			log.Warn().
				Err(err).
				Str("messageId", msg.JobID).
				Int64("elapsedMs", elapsedMillis).
				Msg("Failed to extend visibility for long-running message")
		} else {
			extendedCount++
			log.Debug().
				Str("messageId", msg.JobID).
				Int64("elapsedMs", elapsedMillis).
				Msg("Extended visibility for long-running message")
		}

		return true
	})

	if extendedCount > 0 {
		log.Info().
			Int("count", extendedCount).
			Dur("threshold", m.visibilityConfig.Threshold).
			Msg("Extended visibility for long-running messages")
	}
}

// runLeakDetection runs the memory leak detection loop
// Matches Java's checkForMapLeaks scheduled task (every 30s)
func (m *QueueManager) runLeakDetection() {
	defer m.leakDetectionWg.Done()

	ticker := time.NewTicker(m.leakDetectionConfig.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-m.leakDetectionCtx.Done():
			log.Info().Msg("Memory leak detection stopped")
			return
		case <-ticker.C:
			m.checkForMapLeaks()
		}
	}
}

// checkForMapLeaks detects potential memory leaks in pipeline maps
// Matches Java's checkForMapLeaks implementation:
// - Warns if pipeline map size exceeds total pool capacity
// - This indicates messages are not being removed after processing
func (m *QueueManager) checkForMapLeaks() {
	// Skip if not initialized or shutting down
	m.runningMu.Lock()
	running := m.running
	initialized := m.initialized
	m.runningMu.Unlock()

	if !running || !initialized {
		return
	}

	// Count pipeline map size
	pipelineSize := 0
	m.inPipelineMap.Range(func(_, _ interface{}) bool {
		pipelineSize++
		return true
	})

	// Calculate total pool capacity
	m.poolsMu.RLock()
	totalCapacity := 0
	for _, p := range m.pools {
		totalCapacity += p.GetQueueCapacity()
	}
	m.poolsMu.RUnlock()

	// Add minimum capacity for default pool that might be created
	if totalCapacity == 0 {
		totalCapacity = MinQueueCapacity
	}

	// WARNING: Pipeline map size exceeds total pool capacity
	// This indicates messages are not being removed from the map
	if pipelineSize > totalCapacity {
		message := fmt.Sprintf("inPipelineMap size (%d) exceeds total pool capacity (%d) - possible memory leak",
			pipelineSize, totalCapacity)

		log.Warn().
			Int("pipelineSize", pipelineSize).
			Int("totalCapacity", totalCapacity).
			Msg("LEAK DETECTION: " + message)

		if m.warningService != nil {
			m.warningService.AddWarning("PIPELINE_MAP_LEAK", "WARN", message, "QueueManager")
		}
	}

	// Update pipeline size gauge metric
	metrics.PipelineMapSize.Set(float64(pipelineSize))
}

// GetPipelineSize returns the current size of the pipeline map (for monitoring)
func (m *QueueManager) GetPipelineSize() int {
	size := 0
	m.inPipelineMap.Range(func(_, _ interface{}) bool {
		size++
		return true
	})
	return size
}

// GetTotalPoolCapacity returns the total capacity across all pools (for monitoring)
func (m *QueueManager) GetTotalPoolCapacity() int {
	m.poolsMu.RLock()
	defer m.poolsMu.RUnlock()

	total := 0
	for _, p := range m.pools {
		total += p.GetQueueCapacity()
	}
	return total
}
