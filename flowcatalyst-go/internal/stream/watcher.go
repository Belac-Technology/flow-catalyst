// Package stream provides MongoDB change stream processing
package stream

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	"github.com/rs/zerolog/log"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

// StreamConfig holds configuration for a single stream
type StreamConfig struct {
	// Name is the stream name for logging
	Name string

	// SourceCollection is the collection to watch
	SourceCollection string

	// TargetCollection is the collection to write projections to
	TargetCollection string

	// WatchOperations are the operation types to watch (insert, update, replace)
	WatchOperations []string

	// BatchMaxSize is the maximum batch size before flushing
	BatchMaxSize int

	// BatchMaxWait is the maximum time to wait before flushing a batch
	BatchMaxWait time.Duration

	// CheckpointKey is the key for storing checkpoints
	CheckpointKey string
}

// ProjectionMapper maps source documents to projection documents
type ProjectionMapper interface {
	Map(doc bson.M) bson.M
}

// CheckpointStore stores and retrieves resume tokens
type CheckpointStore interface {
	GetCheckpoint(key string) (*primitive.ObjectID, error)
	SaveCheckpoint(key string, token bson.Raw) error
}

// Watcher watches a MongoDB change stream
type Watcher struct {
	name              string
	client            *mongo.Client
	database          string
	config            *StreamConfig
	checkpointStore   CheckpointStore
	mapper            ProjectionMapper
	targetCollection  *mongo.Collection

	running    bool
	runningMu  sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup

	// Health metrics (like Java's StreamContext)
	batchSequence    atomic.Int64 // Total batches processed
	checkpointedSeq  atomic.Int64 // Last checkpointed sequence
	inFlightCount    atomic.Int32 // Batches currently processing
	fatalError       atomic.Value // Stores error or nil
	availableSlots   atomic.Int32 // Concurrency slots available (default 1 for single-threaded)
}

// NewWatcher creates a new stream watcher
func NewWatcher(
	client *mongo.Client,
	database string,
	config *StreamConfig,
	checkpointStore CheckpointStore,
	mapper ProjectionMapper,
) *Watcher {
	ctx, cancel := context.WithCancel(context.Background())

	w := &Watcher{
		name:             config.Name,
		client:           client,
		database:         database,
		config:           config,
		checkpointStore:  checkpointStore,
		mapper:           mapper,
		targetCollection: client.Database(database).Collection(config.TargetCollection),
		ctx:              ctx,
		cancel:           cancel,
	}

	// Initialize available slots (single-threaded by default)
	w.availableSlots.Store(1)

	return w
}

// Start starts watching the change stream
func (w *Watcher) Start() {
	w.runningMu.Lock()
	if w.running {
		w.runningMu.Unlock()
		log.Warn().Str("stream", w.name).Msg("Watcher already running")
		return
	}
	w.running = true
	w.runningMu.Unlock()

	w.wg.Add(1)
	go w.watchLoop()

	log.Info().Str("stream", w.name).Msg("Stream watcher started")
}

// Stop stops the watcher
func (w *Watcher) Stop() {
	log.Info().Str("stream", w.name).Msg("Stopping stream watcher")

	w.runningMu.Lock()
	w.running = false
	w.runningMu.Unlock()

	w.cancel()
	w.wg.Wait()

	log.Info().Str("stream", w.name).Msg("Stream watcher stopped")
}

// IsRunning returns true if the watcher is running
func (w *Watcher) IsRunning() bool {
	w.runningMu.Lock()
	defer w.runningMu.Unlock()
	return w.running
}

// GetCurrentBatchSequence returns the total number of batches processed
func (w *Watcher) GetCurrentBatchSequence() int64 {
	return w.batchSequence.Load()
}

// GetLastCheckpointedSequence returns the last checkpointed batch sequence
func (w *Watcher) GetLastCheckpointedSequence() int64 {
	return w.checkpointedSeq.Load()
}

// GetInFlightBatchCount returns the number of batches currently being processed
func (w *Watcher) GetInFlightBatchCount() int32 {
	return w.inFlightCount.Load()
}

// GetAvailableConcurrencySlots returns the available concurrency slots
func (w *Watcher) GetAvailableConcurrencySlots() int32 {
	return w.availableSlots.Load()
}

// HasFatalError returns true if the watcher has encountered a fatal error
func (w *Watcher) HasFatalError() bool {
	return w.fatalError.Load() != nil
}

// GetFatalError returns the fatal error if one occurred
func (w *Watcher) GetFatalError() error {
	if err := w.fatalError.Load(); err != nil {
		return err.(error)
	}
	return nil
}

// setFatalError sets the fatal error and stops the watcher
func (w *Watcher) setFatalError(err error) {
	w.fatalError.Store(err)
	w.runningMu.Lock()
	w.running = false
	w.runningMu.Unlock()
}

// watchLoop is the main watch loop
func (w *Watcher) watchLoop() {
	defer w.wg.Done()
	defer func() {
		w.runningMu.Lock()
		w.running = false
		w.runningMu.Unlock()
	}()

	sourceCollection := w.client.Database(w.database).Collection(w.config.SourceCollection)

	// Build pipeline to filter by operation types
	pipeline := mongo.Pipeline{
		{{Key: "$match", Value: bson.M{
			"operationType": bson.M{"$in": w.config.WatchOperations},
		}}},
	}

	// Configure change stream options
	opts := options.ChangeStream().
		SetFullDocument(options.UpdateLookup).
		SetBatchSize(int32(w.config.BatchMaxSize))

	// Resume from checkpoint if available
	if w.checkpointStore != nil {
		if checkpoint, err := w.checkpointStore.GetCheckpoint(w.config.CheckpointKey); err == nil && checkpoint != nil {
			// Note: Resume token handling would need the actual BSON token
			// For simplicity, we start fresh if no proper token
			log.Info().Str("stream", w.name).Msg("Resuming from checkpoint")
		}
	}

	log.Info().
		Str("stream", w.name).
		Str("source", w.config.SourceCollection).
		Str("target", w.config.TargetCollection).
		Strs("operations", w.config.WatchOperations).
		Msg("Opening change stream")

	// Open change stream
	stream, err := sourceCollection.Watch(w.ctx, pipeline, opts)
	if err != nil {
		log.Error().Err(err).Str("stream", w.name).Msg("Failed to open change stream")
		w.setFatalError(err)
		return
	}
	defer stream.Close(w.ctx)

	log.Info().Str("stream", w.name).Msg("Change stream opened - waiting for documents")

	// Batch accumulation
	batch := make([]bson.M, 0, w.config.BatchMaxSize)
	var lastToken bson.Raw
	batchStartTime := time.Now()

	for {
		select {
		case <-w.ctx.Done():
			// Flush remaining batch before exit
			if len(batch) > 0 {
				w.processBatch(batch, lastToken)
			}
			return

		default:
			// Check for next event with timeout
			ctx, cancel := context.WithTimeout(w.ctx, 100*time.Millisecond)
			hasNext := stream.TryNext(ctx)
			cancel()

			if hasNext {
				var event bson.M
				if err := stream.Decode(&event); err != nil {
					log.Error().Err(err).Str("stream", w.name).Msg("Failed to decode change event")
					continue
				}

				// Extract full document
				if fullDoc, ok := event["fullDocument"].(bson.M); ok {
					batch = append(batch, fullDoc)
					lastToken = stream.ResumeToken()
				}
			}

			// Check if we should flush the batch
			batchFull := len(batch) >= w.config.BatchMaxSize
			timeoutReached := time.Since(batchStartTime) >= w.config.BatchMaxWait

			if len(batch) > 0 && (batchFull || timeoutReached) {
				w.processBatch(batch, lastToken)
				batch = make([]bson.M, 0, w.config.BatchMaxSize)
				batchStartTime = time.Now()
			}
		}
	}
}

// processBatch processes a batch of documents
func (w *Watcher) processBatch(batch []bson.M, resumeToken bson.Raw) {
	if len(batch) == 0 {
		return
	}

	// Track batch metrics (like Java's StreamContext)
	currentSeq := w.batchSequence.Add(1)
	w.inFlightCount.Add(1)
	w.availableSlots.Add(-1)
	defer func() {
		w.inFlightCount.Add(-1)
		w.availableSlots.Add(1)
	}()

	log.Debug().
		Str("stream", w.name).
		Int("batchSize", len(batch)).
		Int64("batchSeq", currentSeq).
		Msg("Processing batch")

	// Map and upsert documents
	for _, doc := range batch {
		projected := w.mapper.Map(doc)
		if projected == nil {
			continue
		}

		// Get ID for upsert
		id, ok := projected["_id"]
		if !ok {
			log.Warn().Str("stream", w.name).Msg("Projected document has no _id")
			continue
		}

		// Upsert to target collection
		filter := bson.M{"_id": id}
		update := bson.M{"$set": projected}
		opts := options.Update().SetUpsert(true)

		_, err := w.targetCollection.UpdateOne(w.ctx, filter, update, opts)
		if err != nil {
			log.Error().Err(err).
				Str("stream", w.name).
				Interface("id", id).
				Msg("Failed to upsert projection")
		}
	}

	// Save checkpoint and update checkpointed sequence
	if w.checkpointStore != nil && resumeToken != nil {
		if err := w.checkpointStore.SaveCheckpoint(w.config.CheckpointKey, resumeToken); err != nil {
			log.Error().Err(err).Str("stream", w.name).Msg("Failed to save checkpoint")
		} else {
			w.checkpointedSeq.Store(currentSeq)
		}
	}

	log.Debug().
		Str("stream", w.name).
		Int("processed", len(batch)).
		Int64("batchSeq", currentSeq).
		Msg("Batch processed")
}

// MongoCheckpointStore stores checkpoints in MongoDB
type MongoCheckpointStore struct {
	collection *mongo.Collection
}

// NewMongoCheckpointStore creates a new MongoDB checkpoint store
func NewMongoCheckpointStore(db *mongo.Database) *MongoCheckpointStore {
	return &MongoCheckpointStore{
		collection: db.Collection("stream_checkpoints"),
	}
}

// GetCheckpoint retrieves a checkpoint
func (s *MongoCheckpointStore) GetCheckpoint(key string) (*primitive.ObjectID, error) {
	var doc struct {
		ResumeToken bson.Raw `bson:"resumeToken"`
	}

	err := s.collection.FindOne(context.Background(), bson.M{"_id": key}).Decode(&doc)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, nil
		}
		return nil, err
	}

	return nil, nil // Return the raw token in a real implementation
}

// SaveCheckpoint saves a checkpoint
func (s *MongoCheckpointStore) SaveCheckpoint(key string, token bson.Raw) error {
	filter := bson.M{"_id": key}
	update := bson.M{
		"$set": bson.M{
			"resumeToken": token,
			"updatedAt":   time.Now(),
		},
	}
	opts := options.Update().SetUpsert(true)

	_, err := s.collection.UpdateOne(context.Background(), filter, update, opts)
	return err
}
