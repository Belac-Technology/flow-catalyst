package tech.flowcatalyst.streamprocessor.stream;

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
import tech.flowcatalyst.streamprocessor.dispatch.BatchDispatcher;
import tech.flowcatalyst.streamprocessor.dispatch.CheckpointTracker;

import java.util.ArrayList;
import java.util.List;
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
    private final String checkpointKey;

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
     * @param checkpointKey     key for storing checkpoints
     */
    public StreamWatcher(String streamName, MongoClient mongoClient,
                         StreamProcessorConfig rootConfig, StreamConfig streamConfig,
                         CheckpointStore checkpointStore, BatchDispatcher dispatcher,
                         CheckpointTracker checkpointTracker, String checkpointKey) {
        this.streamName = streamName;
        this.mongoClient = mongoClient;
        this.rootConfig = rootConfig;
        this.streamConfig = streamConfig;
        this.checkpointStore = checkpointStore;
        this.dispatcher = dispatcher;
        this.checkpointTracker = checkpointTracker;
        this.checkpointKey = checkpointKey;
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

    /**
     * Main watch loop - reads from change stream and accumulates batches.
     */
    private void watchLoop() {
        MongoCollection<Document> sourceCollection = mongoClient
                .getDatabase(rootConfig.database())
                .getCollection(streamConfig.sourceCollection());

        // Resume from checkpoint if available
        BsonDocument resumeToken;
        try {
            resumeToken = checkpointStore.getCheckpoint(checkpointKey).orElse(null);
        } catch (CheckpointStore.CheckpointUnavailableException e) {
            LOG.severe("[" + streamName + "] Cannot start - checkpoint store unavailable: " + e.getMessage());
            handleFatalError(e);
            return;
        }

        // Build pipeline to filter by operation types
        List<String> operations = streamConfig.watchOperations();
        List<Bson> pipeline = List.of(
                Aggregates.match(Filters.in("operationType", operations))
        );

        ChangeStreamIterable<Document> stream = sourceCollection
                .watch(pipeline)
                .fullDocument(FullDocument.UPDATE_LOOKUP);  // Include full document for updates

        if (resumeToken != null) {
            stream = stream.resumeAfter(resumeToken);
            LOG.info("[" + streamName + "] Resuming from checkpoint");
        } else {
            LOG.info("[" + streamName + "] Starting from beginning");
        }

        LOG.info("[" + streamName + "] Opening change stream cursor on " +
                rootConfig.database() + "." + streamConfig.sourceCollection() +
                " (operations: " + operations + ")");

        try (MongoCursor<ChangeStreamDocument<Document>> cursor = stream.iterator()) {
            LOG.info("[" + streamName + "] Change stream cursor opened - waiting for documents...");
            List<Document> batch = new ArrayList<>(streamConfig.batchMaxSize());
            BsonDocument lastToken = null;
            String lastOperationType = null;
            long batchStartTime = System.currentTimeMillis();

            while (running) {
                // Check for fatal errors in batch processing
                if (checkpointTracker.hasFatalError()) {
                    LOG.severe("[" + streamName + "] Fatal error detected - stopping watcher");
                    break;
                }

                // Non-blocking check for next event
                ChangeStreamDocument<Document> change = null;
                try {
                    change = cursor.tryNext();
                } catch (Exception e) {
                    if (!running) {
                        break; // Expected during shutdown
                    }
                    throw e;
                }

                if (change != null && change.getFullDocument() != null) {
                    batch.add(change.getFullDocument());
                    lastToken = change.getResumeToken();
                    lastOperationType = change.getOperationType().getValue();
                }

                // Check if we should flush the batch
                boolean batchFull = batch.size() >= streamConfig.batchMaxSize();
                boolean timeoutReached = (System.currentTimeMillis() - batchStartTime) >= streamConfig.batchMaxWaitMs();

                if (!batch.isEmpty() && (batchFull || timeoutReached)) {
                    // Dispatch the batch
                    dispatcher.dispatch(new ArrayList<>(batch), lastToken, lastOperationType);
                    batch.clear();
                    batchStartTime = System.currentTimeMillis();
                }

                // Small sleep if no events to prevent tight loop
                if (change == null) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                LOG.severe("[" + streamName + "] Change stream watcher failed: " + e.getMessage());
                e.printStackTrace();
                handleFatalError(e);
            }
        } finally {
            running = false;
            LOG.info("[" + streamName + "] StreamWatcher stopped");
        }
    }

    /**
     * Handle a fatal error by triggering shutdown.
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
