package tech.flowcatalyst.eventprocessor.stream;

import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import io.quarkus.runtime.Quarkus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import tech.flowcatalyst.eventprocessor.checkpoint.CheckpointStore;
import tech.flowcatalyst.eventprocessor.config.EventProcessorConfig;
import tech.flowcatalyst.eventprocessor.dispatch.BatchDispatcher;
import tech.flowcatalyst.eventprocessor.dispatch.CheckpointTracker;
import tech.flowcatalyst.standby.StandbyService;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Watches the MongoDB change stream on the events collection and dispatches
 * batches for processing.
 *
 * Key behaviors:
 * - Only runs if this instance is the primary (hot standby)
 * - Resumes from Redis checkpoint on restart
 * - Accumulates events into batches (max size or timeout)
 * - Dispatches batches to BatchDispatcher for parallel processing
 */
@ApplicationScoped
public class ChangeStreamWatcher {

    private static final Logger LOG = Logger.getLogger(ChangeStreamWatcher.class.getName());

    @Inject
    EventProcessorConfig config;

    @Inject
    MongoClient mongoClient;

    @Inject
    CheckpointStore checkpointStore;

    @Inject
    BatchDispatcher dispatcher;

    @Inject
    CheckpointTracker checkpointTracker;

    @Inject
    Instance<StandbyService> standbyService;

    private volatile boolean running = false;
    private volatile Thread watchThread;

    /**
     * Start watching the change stream.
     *
     * If hot standby is enabled and this instance is not primary,
     * the watcher will wait until it becomes primary.
     */
    public void start() {
        if (running) {
            LOG.warning("ChangeStreamWatcher already running");
            return;
        }

        // Check standby mode
        if (standbyService.isResolvable()) {
            StandbyService standby = standbyService.get();
            if (!standby.isPrimary()) {
                LOG.info("Standby mode - waiting for primary status before starting change stream");
                // Schedule periodic check for primary status
                scheduleStandbyCheck();
                return;
            }
        }

        startWatching();
    }

    /**
     * Stop watching the change stream.
     */
    public void stop() {
        LOG.info("Stopping ChangeStreamWatcher");
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
     * Start the actual change stream watch loop.
     */
    private void startWatching() {
        running = true;
        watchThread = Thread.startVirtualThread(this::watchLoop);
        LOG.info("ChangeStreamWatcher started");
    }

    /**
     * Schedule periodic check for primary status when in standby mode.
     */
    private void scheduleStandbyCheck() {
        Thread.startVirtualThread(() -> {
            while (!running && standbyService.isResolvable()) {
                try {
                    Thread.sleep(5000); // Check every 5 seconds
                    if (standbyService.get().isPrimary()) {
                        LOG.info("Became primary - starting change stream watcher");
                        startWatching();
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /**
     * Main watch loop - reads from change stream and accumulates batches.
     */
    private void watchLoop() {
        MongoCollection<Document> eventsCollection = mongoClient
                .getDatabase(config.database())
                .getCollection(config.sourceCollection());

        // Resume from checkpoint if available
        BsonDocument resumeToken;
        try {
            resumeToken = checkpointStore.getCheckpoint().orElse(null);
        } catch (CheckpointStore.CheckpointUnavailableException e) {
            LOG.severe("Cannot start change stream - checkpoint store unavailable: " + e.getMessage());
            handleFatalError(e);
            return;
        }

        // Pipeline to filter only insert operations
        List<Bson> pipeline = List.of(
                Aggregates.match(Filters.eq("operationType", "insert"))
        );

        ChangeStreamIterable<Document> stream = eventsCollection
                .watch(pipeline)
                .fullDocument(FullDocument.DEFAULT);

        if (resumeToken != null) {
            stream = stream.resumeAfter(resumeToken);
            LOG.info("Resuming change stream from checkpoint");
        } else {
            LOG.info("Starting change stream from beginning");
        }

        LOG.info("Opening change stream cursor on " + config.database() + "." + config.sourceCollection());
        try (MongoCursor<ChangeStreamDocument<Document>> cursor = stream.iterator()) {
            LOG.info("Change stream cursor opened successfully - waiting for events...");
            List<Document> batch = new ArrayList<>(config.batchMaxSize());
            BsonDocument lastToken = null;
            long batchStartTime = System.currentTimeMillis();

            while (running) {
                // Check for fatal errors in batch processing
                if (checkpointTracker.hasFatalError()) {
                    LOG.severe("Fatal error detected - stopping change stream watcher");
                    break;
                }

                // Non-blocking check for next event
                ChangeStreamDocument<Document> change = null;
                try {
                    // tryNext() returns null immediately if no event available
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
                }

                // Check if we should flush the batch
                boolean batchFull = batch.size() >= config.batchMaxSize();
                boolean timeoutReached = (System.currentTimeMillis() - batchStartTime) >= config.batchMaxWaitMs();

                if (!batch.isEmpty() && (batchFull || timeoutReached)) {
                    // Dispatch the batch
                    dispatcher.dispatch(new ArrayList<>(batch), lastToken);
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
                LOG.severe("Change stream watcher failed: " + e.getMessage());
                e.printStackTrace();
                handleFatalError(e);
            }
        } finally {
            running = false;
            LOG.info("ChangeStreamWatcher stopped");
        }
    }

    /**
     * Handle a fatal error by triggering shutdown.
     */
    private void handleFatalError(Exception e) {
        LOG.severe("FATAL: Event processor encountered unrecoverable error - triggering shutdown");
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Brief delay to flush logs
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            Quarkus.asyncExit(1);
        }, "change-stream-shutdown-thread").start();
    }
}
