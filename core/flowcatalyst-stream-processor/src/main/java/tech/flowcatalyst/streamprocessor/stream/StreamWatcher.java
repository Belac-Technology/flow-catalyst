package tech.flowcatalyst.streamprocessor.stream;

import com.mongodb.MongoCommandException;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import io.quarkus.runtime.Quarkus;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import tech.flowcatalyst.streamprocessor.checkpoint.CheckpointStore;
import tech.flowcatalyst.streamprocessor.config.StreamConfig;
import tech.flowcatalyst.streamprocessor.config.StreamProcessorConfig;
import tech.flowcatalyst.streamprocessor.dispatch.AggregateTracker;
import tech.flowcatalyst.streamprocessor.dispatch.BatchDispatcher;
import tech.flowcatalyst.streamprocessor.dispatch.CheckpointTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches the MongoDB change stream for a single stream and dispatches
 * batches for processing.
 *
 * <p>Key behaviors:</p>
 * <ul>
 *   <li>Watches the source collection for configured operations (insert, update, replace)</li>
 *   <li>Resumes from checkpoint on restart</li>
 *   <li>Accumulates documents into batches (max size or timeout)</li>
 *   <li>Dispatches batches to BatchDispatcher for parallel processing</li>
 * </ul>
 *
 * <p>Note: This class is NOT a CDI bean. Each stream gets its own instance
 * created by the StreamProcessorStarter.</p>
 */
public class StreamWatcher {

    private static final Logger LOG = Logger.getLogger(StreamWatcher.class.getName());

    private final String streamName;
    private final MongoClient mongoClient;
    private final StreamProcessorConfig rootConfig;
    private final StreamConfig streamConfig;
    private final CheckpointStore checkpointStore;
    private final BatchDispatcher dispatcher;
    private final CheckpointTracker checkpointTracker;
    private final AggregateTracker aggregateTracker;
    private final String checkpointKey;
    private final String aggregateIdField;

    private volatile boolean running = false;
    private volatile Thread watchThread;

    /**
     * Create a new stream watcher.
     *
     * @param streamName        name of the stream (for logging)
     * @param mongoClient       MongoDB client
     * @param rootConfig        root stream processor configuration
     * @param streamConfig      this stream's configuration
     * @param checkpointStore   checkpoint store for resume tokens
     * @param dispatcher        batch dispatcher for this stream
     * @param checkpointTracker checkpoint tracker for this stream
     * @param aggregateTracker  aggregate tracker for ordering guarantees
     * @param checkpointKey     key for storing checkpoints
     */
    public StreamWatcher(String streamName, MongoClient mongoClient,
                         StreamProcessorConfig rootConfig, StreamConfig streamConfig,
                         CheckpointStore checkpointStore, BatchDispatcher dispatcher,
                         CheckpointTracker checkpointTracker, AggregateTracker aggregateTracker,
                         String checkpointKey) {
        this.streamName = streamName;
        this.mongoClient = mongoClient;
        this.rootConfig = rootConfig;
        this.streamConfig = streamConfig;
        this.checkpointStore = checkpointStore;
        this.dispatcher = dispatcher;
        this.checkpointTracker = checkpointTracker;
        this.aggregateTracker = aggregateTracker;
        this.checkpointKey = checkpointKey;
        this.aggregateIdField = streamConfig.aggregateIdField();
    }

    /**
     * Start watching the change stream.
     */
    public void start() {
        if (running) {
            LOG.warning("[" + streamName + "] StreamWatcher already running");
            return;
        }

        running = true;
        watchThread = Thread.startVirtualThread(this::watchLoop);
        LOG.info("[" + streamName + "] StreamWatcher started");
    }

    /**
     * Stop watching the change stream.
     */
    public void stop() {
        LOG.info("[" + streamName + "] Stopping StreamWatcher");
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    /**
     * Check if the watcher is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the stream name.
     */
    public String getStreamName() {
        return streamName;
    }

    // MongoDB error code for stale resume token (change stream history lost)
    private static final int CHANGE_STREAM_HISTORY_LOST = 286;

    // Reconnection settings
    private static final long INITIAL_BACKOFF_MS = 5000;      // 5 seconds
    private static final long MAX_BACKOFF_MS = 60000;         // 60 seconds
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /**
     * Main watch loop with automatic reconnection.
     *
     * <p>This loop wraps cursor creation in a retry mechanism that handles:
     * <ul>
     *   <li>Connection failures - exponential backoff with automatic reconnection</li>
     *   <li>Stale resume tokens - clears checkpoint and starts from current position</li>
     *   <li>Other transient errors - logs and retries with backoff</li>
     * </ul>
     */
    private void watchLoop() {
        MongoCollection<Document> sourceCollection = mongoClient
                .getDatabase(rootConfig.database())
                .getCollection(streamConfig.sourceCollection());

        List<String> operations = streamConfig.watchOperations();
        List<Bson> pipeline = List.of(
                Aggregates.match(Filters.in("operationType", operations))
        );

        int consecutiveFailures = 0;
        long backoffMs = INITIAL_BACKOFF_MS;

        while (running) {
            BsonDocument resumeToken = null;

            // Try to get resume token from checkpoint
            try {
                resumeToken = checkpointStore.getCheckpoint(checkpointKey).orElse(null);
            } catch (CheckpointStore.CheckpointUnavailableException e) {
                LOG.warning("[" + streamName + "] Checkpoint store unavailable, starting from current position: " + e.getMessage());
                // Continue without resume token - better than not starting at all
            }

            try {
                // Create change stream
                ChangeStreamIterable<Document> stream = sourceCollection
                        .watch(pipeline)
                        .fullDocument(FullDocument.UPDATE_LOOKUP);

                if (resumeToken != null) {
                    stream = stream.resumeAfter(resumeToken);
                    LOG.info("[" + streamName + "] Resuming from checkpoint");
                } else {
                    LOG.info("[" + streamName + "] Starting from current position");
                }

                LOG.info("[" + streamName + "] Opening change stream cursor on " +
                        rootConfig.database() + "." + streamConfig.sourceCollection() +
                        " (operations: " + operations + ")");

                // Process the change stream
                try (MongoCursor<ChangeStreamDocument<Document>> cursor = stream.iterator()) {
                    LOG.info("[" + streamName + "] Change stream cursor opened - waiting for documents...");

                    // Reset backoff on successful connection
                    consecutiveFailures = 0;
                    backoffMs = INITIAL_BACKOFF_MS;

                    processCursor(cursor);
                }

            } catch (MongoCommandException e) {
                if (!running) break;

                if (e.getErrorCode() == CHANGE_STREAM_HISTORY_LOST) {
                    // Resume token is stale - clear checkpoint and start fresh
                    LOG.severe("[" + streamName + "] Resume token expired (change stream history lost). " +
                            "Clearing checkpoint and starting from current position. SOME EVENTS MAY HAVE BEEN MISSED.");
                    checkpointStore.clearCheckpoint(checkpointKey);
                    // Don't backoff - try immediately with fresh start
                    continue;
                }

                // Other MongoDB errors - log and retry with backoff
                consecutiveFailures++;
                LOG.log(Level.WARNING, "[" + streamName + "] MongoDB command error (attempt " +
                        consecutiveFailures + "), reconnecting in " + backoffMs + "ms: " + e.getMessage(), e);
                sleepWithBackoff(backoffMs);
                backoffMs = Math.min((long) (backoffMs * BACKOFF_MULTIPLIER), MAX_BACKOFF_MS);

            } catch (Exception e) {
                if (!running) break;

                consecutiveFailures++;
                LOG.log(Level.WARNING, "[" + streamName + "] Change stream error (attempt " +
                        consecutiveFailures + "), reconnecting in " + backoffMs + "ms: " + e.getMessage(), e);
                sleepWithBackoff(backoffMs);
                backoffMs = Math.min((long) (backoffMs * BACKOFF_MULTIPLIER), MAX_BACKOFF_MS);
            }
        }

        running = false;
        LOG.info("[" + streamName + "] StreamWatcher stopped");
    }

    /**
     * Process events from the change stream cursor.
     *
     * @param cursor the change stream cursor
     */
    private void processCursor(MongoCursor<ChangeStreamDocument<Document>> cursor) {
        List<Document> batch = new ArrayList<>(streamConfig.batchMaxSize());
        Set<String> batchAggregateIds = new HashSet<>();
        BsonDocument lastToken = null;
        String lastOperationType = null;
        long batchStartTime = System.currentTimeMillis();

        while (running) {
            // Check for fatal errors in batch processing
            if (checkpointTracker.hasFatalError()) {
                LOG.severe("[" + streamName + "] Fatal error detected in batch processing - stopping watcher");
                handleFatalError(checkpointTracker.getFatalError());
                return;
            }

            // Non-blocking check for next event
            ChangeStreamDocument<Document> change = null;
            try {
                change = cursor.tryNext();
            } catch (Exception e) {
                if (!running) {
                    return; // Expected during shutdown
                }
                throw e; // Let outer loop handle reconnection
            }

            if (change != null && change.getFullDocument() != null) {
                Document doc = change.getFullDocument();
                BsonDocument resumeToken = change.getResumeToken();
                String aggregateId = getAggregateId(doc);

                // Check if this aggregate is already in-flight (processing in another batch)
                if (aggregateId != null && aggregateTracker.isInFlight(aggregateId)) {
                    LOG.fine("[" + streamName + "] Aggregate " + aggregateId +
                            " is in-flight, queueing document");
                    aggregateTracker.addPending(new AggregateTracker.PendingDocument(
                            aggregateId, doc, resumeToken));
                    continue; // Skip to next document
                }

                // Check if this aggregate is already in the current batch
                if (aggregateId != null && batchAggregateIds.contains(aggregateId)) {
                    // Flush current batch first to maintain ordering
                    if (!batch.isEmpty()) {
                        dispatcher.dispatch(new ArrayList<>(batch),
                                new HashSet<>(batchAggregateIds), lastToken, lastOperationType);
                        batch.clear();
                        batchAggregateIds.clear();
                        batchStartTime = System.currentTimeMillis();
                    }
                }

                // Add to current batch
                batch.add(doc);
                if (aggregateId != null) {
                    batchAggregateIds.add(aggregateId);
                }
                lastToken = resumeToken;
                lastOperationType = change.getOperationType().getValue();
            }

            // Check if we should flush the batch
            boolean batchFull = batch.size() >= streamConfig.batchMaxSize();
            boolean timeoutReached = (System.currentTimeMillis() - batchStartTime) >= streamConfig.batchMaxWaitMs();

            if (!batch.isEmpty() && (batchFull || timeoutReached)) {
                // Dispatch the batch
                dispatcher.dispatch(new ArrayList<>(batch),
                        new HashSet<>(batchAggregateIds), lastToken, lastOperationType);
                batch.clear();
                batchAggregateIds.clear();
                batchStartTime = System.currentTimeMillis();
            }

            // Small sleep if no events to prevent tight loop
            if (change == null) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Extract the aggregate ID from a document.
     *
     * @param doc the document
     * @return the aggregate ID, or null if not found
     */
    private String getAggregateId(Document doc) {
        if (doc == null || aggregateIdField == null || aggregateIdField.isEmpty()) {
            return null;
        }

        Object value = doc.get(aggregateIdField);
        if (value == null) {
            return null;
        }

        // Handle ObjectId
        if (value instanceof org.bson.types.ObjectId) {
            return ((org.bson.types.ObjectId) value).toHexString();
        }

        // Handle String
        if (value instanceof String) {
            return (String) value;
        }

        // Fallback to toString
        return value.toString();
    }

    /**
     * Sleep for the backoff period, handling interruption.
     */
    private void sleepWithBackoff(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Handle a fatal error by triggering shutdown.
     * Only called for truly unrecoverable errors (e.g., batch processing failures).
     */
    private void handleFatalError(Exception e) {
        LOG.severe("[" + streamName + "] FATAL: Stream watcher encountered unrecoverable error - triggering shutdown");
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Brief delay to flush logs
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            Quarkus.asyncExit(1);
        }, "stream-watcher-shutdown-thread").start();
    }
}
