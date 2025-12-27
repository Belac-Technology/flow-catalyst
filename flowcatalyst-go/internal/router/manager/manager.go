// Package manager provides the queue manager for the message router
package manager

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/rs/zerolog/log"
	"go.mongodb.org/mongo-driver/mongo"

	"go.flowcatalyst.tech/internal/common/tsid"
	"go.flowcatalyst.tech/internal/platform/dispatchpool"
	"go.flowcatalyst.tech/internal/queue"
	"go.flowcatalyst.tech/internal/router/mediator"
	"go.flowcatalyst.tech/internal/router/model"
	"go.flowcatalyst.tech/internal/router/pool"
)

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
}

// DefaultConfigSyncConfig returns sensible defaults
func DefaultConfigSyncConfig() *ConfigSyncConfig {
	return &ConfigSyncConfig{
		Enabled:  false,
		Interval: 5 * time.Minute,
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
}

// NewQueueManager creates a new queue manager
func NewQueueManager(mediatorCfg *mediator.HTTPMediatorConfig) *QueueManager {
	httpMediator := mediator.NewHTTPMediator(mediatorCfg)

	qm := &QueueManager{
		pools:         make(map[string]*pool.ProcessPool),
		mediator:      httpMediator,
		syncConfig:    DefaultConfigSyncConfig(),
		cleanupConfig: DefaultPipelineCleanupConfig(),
	}

	// Create message callback that references the manager
	qm.messageCallback = &MessageCallbackImpl{manager: qm}

	return qm
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

	// Get or create pool
	poolCfg := &PoolConfig{
		Code:          msg.DispatchPoolID,
		Concurrency:   10, // Default
		QueueCapacity: 500,
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

		// Get or create pool
		poolCfg := &PoolConfig{
			Code:          poolCode,
			Concurrency:   10, // Default
			QueueCapacity: 500,
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
}

// NewConsumer creates a new consumer
func NewConsumer(manager *QueueManager, queueConsumer queue.Consumer) *Consumer {
	ctx, cancel := context.WithCancel(context.Background())
	return &Consumer{
		manager:  manager,
		consumer: queueConsumer,
		ctx:      ctx,
		cancel:   cancel,
	}
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

// Router ties together all message router components
type Router struct {
	manager  *QueueManager
	consumer *Consumer
}

// NewRouter creates a new message router
func NewRouter(queueConsumer queue.Consumer, mediatorCfg *mediator.HTTPMediatorConfig) *Router {
	manager := NewQueueManager(mediatorCfg)

	var consumer *Consumer
	if queueConsumer != nil {
		consumer = NewConsumer(manager, queueConsumer)
	}

	return &Router{
		manager:  manager,
		consumer: consumer,
	}
}

// Start starts the router
func (r *Router) Start() {
	r.manager.Start()
	if r.consumer != nil {
		r.consumer.Start()
	}
	log.Info().Msg("Message router started")
}

// Stop stops the router
func (r *Router) Stop() {
	if r.consumer != nil {
		r.consumer.Stop()
	}
	r.manager.Stop()
	log.Info().Msg("Message router stopped")
}

// Manager returns the queue manager
func (r *Router) Manager() *QueueManager {
	return r.manager
}

// GenerateBatchID generates a new batch ID
func GenerateBatchID() string {
	return tsid.Generate()
}

// runConfigSync runs the pool configuration sync loop
func (m *QueueManager) runConfigSync() {
	defer m.syncWg.Done()

	// Do initial sync immediately
	m.syncPoolConfig()

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

// syncPoolConfig syncs pool configurations from the database
func (m *QueueManager) syncPoolConfig() {
	ctx, cancel := context.WithTimeout(m.syncCtx, 30*time.Second)
	defer cancel()

	configs, err := m.poolRepo.FindAllEnabled(ctx)
	if err != nil {
		log.Error().Err(err).Msg("Failed to fetch pool configs from database")
		return
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
			// Create new pool from database config
			poolCfg := &PoolConfig{
				Code:               cfg.Code,
				Concurrency:        cfg.GetConcurrencyOrDefault(10),
				QueueCapacity:      cfg.GetQueueCapacityOrDefault(500),
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
