package tech.flowcatalyst.messagerouter.manager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.client.MessageRouterConfigClient;
import tech.flowcatalyst.messagerouter.config.MessageRouterConfig;
import tech.flowcatalyst.messagerouter.config.ProcessingPool;
import tech.flowcatalyst.messagerouter.config.QueueConfig;
import tech.flowcatalyst.messagerouter.consumer.QueueConsumer;
import tech.flowcatalyst.messagerouter.factory.MediatorFactory;
import tech.flowcatalyst.messagerouter.factory.QueueConsumerFactory;
import tech.flowcatalyst.messagerouter.mediator.Mediator;
import tech.flowcatalyst.messagerouter.metrics.PoolMetricsService;
import tech.flowcatalyst.messagerouter.model.MessagePointer;
import tech.flowcatalyst.messagerouter.pool.ProcessPool;
import tech.flowcatalyst.messagerouter.pool.ProcessPoolImpl;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class QueueManager implements MessageCallback {

    private static final Logger LOG = Logger.getLogger(QueueManager.class);
    private static final int MIN_QUEUE_CAPACITY = 500;
    private static final int QUEUE_CAPACITY_MULTIPLIER = 10;
    private static final String DEFAULT_POOL_CODE = "DEFAULT-POOL";
    private static final int DEFAULT_POOL_CONCURRENCY = 20;

    @ConfigProperty(name = "message-router.enabled", defaultValue = "true")
    boolean messageRouterEnabled;

    @ConfigProperty(name = "message-router.max-pools", defaultValue = "2000")
    int maxPools;

    @ConfigProperty(name = "message-router.pool-warning-threshold", defaultValue = "1000")
    int poolWarningThreshold;

    @Inject
    @RestClient
    MessageRouterConfigClient configClient;

    @Inject
    QueueConsumerFactory queueConsumerFactory;

    @Inject
    MediatorFactory mediatorFactory;

    @Inject
    tech.flowcatalyst.messagerouter.health.QueueValidationService queueValidationService;

    @Inject
    PoolMetricsService poolMetrics;

    @Inject
    WarningService warningService;

    @Inject
    MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, MessagePointer> inPipelineMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProcessPool> processPools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QueueConsumer> queueConsumers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MessageCallback> messageCallbacks = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    // Gauges for monitoring map sizes to detect memory leaks
    private AtomicInteger inPipelineMapSizeGauge;
    private AtomicInteger messageCallbacksMapSizeGauge;
    private AtomicInteger activePoolCountGauge;

    void onStartup(@Observes StartupEvent event) {
        initializeMetrics();
    }

    /**
     * Initialize Micrometer gauges for map size monitoring
     */
    private void initializeMetrics() {
        // Create gauge for inPipelineMap size
        inPipelineMapSizeGauge = new AtomicInteger(0);
        meterRegistry.gauge(
            "flowcatalyst.queuemanager.pipeline.size",
            List.of(Tag.of("type", "inPipeline")),
            inPipelineMapSizeGauge
        );

        // Create gauge for messageCallbacks size
        messageCallbacksMapSizeGauge = new AtomicInteger(0);
        meterRegistry.gauge(
            "flowcatalyst.queuemanager.callbacks.size",
            List.of(Tag.of("type", "callbacks")),
            messageCallbacksMapSizeGauge
        );

        // Create gauge for active pool count
        activePoolCountGauge = new AtomicInteger(0);
        meterRegistry.gauge(
            "flowcatalyst.queuemanager.pools.active",
            List.of(Tag.of("type", "pools")),
            activePoolCountGauge
        );

        LOG.infof("QueueManager metrics initialized (max pools: %d, warning threshold: %d)",
            maxPools, poolWarningThreshold);
    }

    /**
     * Update map size gauges
     */
    private void updateMapSizeGauges() {
        if (inPipelineMapSizeGauge != null) {
            inPipelineMapSizeGauge.set(inPipelineMap.size());
        }
        if (messageCallbacksMapSizeGauge != null) {
            messageCallbacksMapSizeGauge.set(messageCallbacks.size());
        }
        if (activePoolCountGauge != null) {
            activePoolCountGauge.set(processPools.size());
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        LOG.info("QueueManager shutting down...");
        stopAllConsumers();
        drainAllPools();
        cleanupRemainingMessages();
    }

    /**
     * Clean up any remaining messages in the pipeline during shutdown
     * This ensures messages are properly nacked back to the queue
     */
    private void cleanupRemainingMessages() {
        LOG.info("Cleaning up remaining messages in pipeline...");

        int pipelineSize = inPipelineMap.size();
        int callbacksSize = messageCallbacks.size();

        if (pipelineSize == 0 && callbacksSize == 0) {
            LOG.info("No remaining messages to clean up");
            return;
        }

        LOG.infof("Found %d messages in pipeline and %d callbacks to clean up", pipelineSize, callbacksSize);

        int nackedCount = 0;
        int errorCount = 0;
        long startTime = System.currentTimeMillis();

        // Nack all messages still in pipeline
        for (MessagePointer message : inPipelineMap.values()) {
            try {
                MessageCallback callback = messageCallbacks.get(message.id());
                if (callback != null) {
                    callback.nack(message);
                    nackedCount++;
                    LOG.debugf("Nacked message [%s] during shutdown", message.id());
                } else {
                    LOG.warnf("No callback found for message [%s] during shutdown cleanup", message.id());
                }
            } catch (Exception e) {
                errorCount++;
                LOG.errorf(e, "Error nacking message [%s] during shutdown: %s", message.id(), e.getMessage());
            }
        }

        // Clear both maps
        inPipelineMap.clear();
        messageCallbacks.clear();

        // Update gauges one final time
        updateMapSizeGauges();

        long durationMs = System.currentTimeMillis() - startTime;

        LOG.infof("Shutdown cleanup completed in %d ms - nacked: %d, errors: %d, total: %d",
            durationMs, nackedCount, errorCount, pipelineSize);

        // Add warning if there were errors during cleanup
        if (errorCount > 0) {
            warningService.addWarning(
                "SHUTDOWN_CLEANUP_ERRORS",
                "WARN",
                String.format("Encountered %d errors while nacking %d messages during shutdown", errorCount, pipelineSize),
                "QueueManager"
            );
        }
    }

    /**
     * Periodically check for potential memory leaks in the pipeline and callback maps
     * Runs every 30 seconds to detect anomalies early
     */
    @Scheduled(every = "30s")
    void checkForMapLeaks() {
        if (!initialized) {
            // Skip check until system is initialized
            return;
        }

        int pipelineSize = inPipelineMap.size();
        int callbacksSize = messageCallbacks.size();

        // Calculate total pool capacity (sum of all pool queue capacities)
        int totalCapacity = processPools.values().stream()
            .mapToInt(pool -> pool.getConcurrency() * QUEUE_CAPACITY_MULTIPLIER)
            .sum();

        // Add minimum capacity for default pool that might be created
        totalCapacity = Math.max(totalCapacity, MIN_QUEUE_CAPACITY);

        // WARNING: Pipeline map size exceeds total pool capacity
        // This indicates messages are not being removed from the map
        if (pipelineSize > totalCapacity) {
            warningService.addWarning(
                "PIPELINE_MAP_LEAK",
                "WARN",
                String.format("inPipelineMap size (%d) exceeds total pool capacity (%d) - possible memory leak",
                    pipelineSize, totalCapacity),
                "QueueManager"
            );
            LOG.warnf("LEAK DETECTION: inPipelineMap size (%d) > total capacity (%d)", pipelineSize, totalCapacity);
        }

        // WARNING: Map size mismatch
        // Pipeline and callbacks maps should have same size (one callback per message)
        int sizeDifference = Math.abs(pipelineSize - callbacksSize);
        if (sizeDifference > 10) {
            warningService.addWarning(
                "MAP_SIZE_MISMATCH",
                "WARN",
                String.format("Map size mismatch - pipeline: %d, callbacks: %d (diff: %d)",
                    pipelineSize, callbacksSize, sizeDifference),
                "QueueManager"
            );
            LOG.warnf("LEAK DETECTION: Map size mismatch - pipeline: %d, callbacks: %d", pipelineSize, callbacksSize);
        }

        // INFO: Log current map sizes for monitoring
        if (LOG.isDebugEnabled()) {
            LOG.debugf("Map sizes - pipeline: %d, callbacks: %d, total capacity: %d",
                pipelineSize, callbacksSize, totalCapacity);
        }
    }

    @Scheduled(every = "${message-router.sync-interval:5m}", delay = 2, delayUnit = java.util.concurrent.TimeUnit.SECONDS)
    void scheduledSync() {
        if (!messageRouterEnabled) {
            if (!initialized) {
                LOG.info("Message router is disabled, skipping initialization");
                initialized = true;
            }
            return;
        }

        boolean isInitialSync = !initialized;
        if (isInitialSync) {
            LOG.info("QueueManager initializing on first scheduled sync...");
        } else {
            LOG.info("Running scheduled configuration sync");
        }

        boolean syncSuccess = syncConfiguration(isInitialSync);

        if (isInitialSync) {
            if (syncSuccess) {
                initialized = true;
                LOG.info("QueueManager initialization completed successfully");
            } else {
                LOG.error("Initial configuration sync failed after all retries - shutting down application");
                warningService.addWarning(
                    "CONFIG_SYNC_FAILED",
                    "CRITICAL",
                    "Initial configuration sync failed after 1 minute of retries - application will exit",
                    "QueueManager"
                );
                Quarkus.asyncExit(1);
            }
        } else if (!syncSuccess) {
            LOG.warn("Configuration sync failed - continuing with existing configuration");
            warningService.addWarning(
                "CONFIG_SYNC_FAILED",
                "WARN",
                "Scheduled configuration sync failed - continuing with existing configuration",
                "QueueManager"
            );
        }
    }

    private synchronized boolean syncConfiguration(boolean isInitialSync) {
        // Retry logic: 12 attempts with 5-second delays = 1 minute total
        // For initial sync failures, application will exit
        // For subsequent sync failures, application continues with existing config
        MessageRouterConfig config = null;
        int attempts = 0;
        int maxAttempts = 12;
        int retryDelayMs = 5000;

        while (config == null && attempts < maxAttempts) {
            try {
                attempts++;
                LOG.infof("Fetching queue configuration (attempt %d/%d)...", attempts, maxAttempts);
                config = configClient.getQueueConfig();
            } catch (Exception e) {
                if (attempts >= maxAttempts) {
                    LOG.errorf(e, "Failed to fetch configuration after %d attempts over %d seconds",
                        maxAttempts, (maxAttempts * retryDelayMs) / 1000);
                    return false;
                }
                LOG.warnf("Failed to fetch config (attempt %d/%d), retrying in %d seconds: %s",
                    attempts, maxAttempts, retryDelayMs / 1000, e.getMessage());
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.error("Configuration fetch interrupted");
                    return false;
                }
            }
        }

        if (config == null) {
            LOG.error("Configuration is null after all retries");
            return false;
        }

        try {

            // Step 1: Identify pools to stop and start (keep queues running)
            Map<String, ProcessingPool> newPools = new ConcurrentHashMap<>();
            for (ProcessingPool poolConfig : config.processingPools()) {
                newPools.put(poolConfig.code(), poolConfig);
            }

            // Stop and drain pools that no longer exist or have changed concurrency
            for (Map.Entry<String, ProcessPool> entry : processPools.entrySet()) {
                String poolCode = entry.getKey();
                ProcessPool existingPool = entry.getValue();
                ProcessingPool newPoolConfig = newPools.get(poolCode);

                if (newPoolConfig == null ||
                    newPoolConfig.concurrency() != existingPool.getConcurrency() ||
                    !java.util.Objects.equals(newPoolConfig.rateLimitPerMinute(), existingPool.getRateLimitPerMinute())) {
                    LOG.infof("Stopping and draining pool [%s] (concurrency or rate limit changed)", poolCode);
                    existingPool.drain();
                    processPools.remove(poolCode);

                    // Clean up metrics for removed pool
                    poolMetrics.removePoolMetrics(poolCode);
                    updateMapSizeGauges(); // Update pool count metric
                    LOG.infof("Removed metrics for pool [%s]", poolCode);
                }
            }

            // Step 3: Start new or updated pools
            for (ProcessingPool poolConfig : config.processingPools()) {
                if (!processPools.containsKey(poolConfig.code())) {
                    // Check pool count limits before creating new pool
                    int currentPoolCount = processPools.size();

                    if (currentPoolCount >= maxPools) {
                        LOG.errorf("Cannot create pool [%s]: Maximum pool limit reached (%d/%d). " +
                            "Increase message-router.max-pools or scale up instance size.",
                            poolConfig.code(), currentPoolCount, maxPools);
                        warningService.addWarning(
                            "POOL_LIMIT",
                            "ERROR",
                            String.format("Max pool limit reached (%d/%d) - cannot create pool [%s]",
                                currentPoolCount, maxPools, poolConfig.code()),
                            "QueueManager"
                        );
                        continue; // Skip this pool
                    }

                    if (currentPoolCount >= poolWarningThreshold) {
                        LOG.warnf("Pool count approaching limit: %d/%d (warning threshold: %d). " +
                            "Consider increasing max-pools or scaling instance.",
                            currentPoolCount, maxPools, poolWarningThreshold);
                        warningService.addWarning(
                            "POOL_LIMIT",
                            "WARNING",
                            String.format("Pool count %d approaching limit %d (threshold: %d)",
                                currentPoolCount, maxPools, poolWarningThreshold),
                            "QueueManager"
                        );
                    }

                    // Calculate queue capacity: 10x concurrency with minimum of 500
                    int queueCapacity = Math.max(poolConfig.concurrency() * QUEUE_CAPACITY_MULTIPLIER, MIN_QUEUE_CAPACITY);

                    LOG.infof("Creating new process pool [%s] with concurrency %d and queue capacity %d (pool %d/%d)",
                        poolConfig.code(), poolConfig.concurrency(), queueCapacity, currentPoolCount + 1, maxPools);

                    // Determine mediator type based on pool code
                    tech.flowcatalyst.messagerouter.model.MediationType mediatorType = determineMediatorType(poolConfig.code());
                    Mediator mediator = mediatorFactory.createMediator(mediatorType);

                    ProcessPool pool = new ProcessPoolImpl(
                        poolConfig.code(),
                        poolConfig.concurrency(),
                        queueCapacity,
                        poolConfig.rateLimitPerMinute(),
                        mediator,
                        this,
                        inPipelineMap,
                        poolMetrics,
                        warningService
                    );

                    pool.start();
                    processPools.put(poolConfig.code(), pool);
                    updateMapSizeGauges(); // Update pool count metric
                }
            }

            // Step 4: Incrementally sync queue consumers (no stop-the-world)
            Map<String, QueueConfig> newQueues = new ConcurrentHashMap<>();
            for (QueueConfig queueConfig : config.queues()) {
                String queueIdentifier = queueConfig.queueName() != null
                    ? queueConfig.queueName()
                    : queueConfig.queueUri();
                newQueues.put(queueIdentifier, queueConfig);
            }

            // Stop consumers for queues that no longer exist
            for (Map.Entry<String, QueueConsumer> entry : queueConsumers.entrySet()) {
                String queueIdentifier = entry.getKey();
                if (!newQueues.containsKey(queueIdentifier)) {
                    LOG.infof("Stopping queue consumer for removed queue [%s]", queueIdentifier);
                    QueueConsumer consumer = entry.getValue();
                    consumer.stop();
                    queueConsumers.remove(queueIdentifier);
                }
            }

            // Validate all queues (raises warnings for missing queues but doesn't stop processing)
            LOG.info("Validating queue accessibility...");
            List<String> queueIssues = queueValidationService.validateQueues(config.queues());
            if (!queueIssues.isEmpty()) {
                LOG.warnf("Found %d queue validation issues - will attempt to create consumers anyway", queueIssues.size());
            }

            // Start consumers for new queues (leave existing ones running)
            for (QueueConfig queueConfig : config.queues()) {
                String queueIdentifier = queueConfig.queueName() != null
                    ? queueConfig.queueName()
                    : queueConfig.queueUri();

                if (!queueConsumers.containsKey(queueIdentifier)) {
                    LOG.infof("Creating new queue consumer for [%s] with %d connections",
                        queueIdentifier, config.connections());

                    QueueConsumer consumer = queueConsumerFactory.createConsumer(queueConfig, config.connections());
                    consumer.start();
                    queueConsumers.put(queueIdentifier, consumer);
                } else {
                    LOG.debugf("Queue consumer for [%s] already running, leaving unchanged", queueIdentifier);
                }
            }

            LOG.info("Configuration sync completed successfully");
            return true;

        } catch (Exception e) {
            LOG.error("Failed to sync configuration", e);
            return false;
        }
    }

    private void stopAllConsumers() {
        LOG.info("Stopping all queue consumers");
        for (QueueConsumer consumer : queueConsumers.values()) {
            try {
                consumer.stop();
            } catch (Exception e) {
                LOG.errorf(e, "Error stopping consumer: %s", consumer.getQueueIdentifier());
            }
        }
    }

    private void drainAllPools() {
        LOG.info("Draining all process pools");
        for (ProcessPool pool : processPools.values()) {
            try {
                pool.drain();
            } catch (Exception e) {
                LOG.errorf(e, "Error draining pool: %s", pool.getPoolCode());
            }
        }
    }

    /**
     * Called by queue consumers to route a message to the appropriate process pool
     *
     * @param message the message to route
     * @param callback the callback to invoke for ack/nack
     * @return true if message was routed successfully, false otherwise
     */
    public boolean routeMessage(MessagePointer message, MessageCallback callback) {
        // Check if message is already in pipeline (deduplication)
        if (inPipelineMap.containsKey(message.id())) {
            LOG.debugf("Message [%s] already in pipeline, discarding", message.id());
            return false;
        }

        // Add to pipeline map
        inPipelineMap.put(message.id(), message);
        messageCallbacks.put(message.id(), callback);

        // Update gauges
        updateMapSizeGauges();

        // Route to appropriate process pool
        ProcessPool pool = processPools.get(message.poolCode());
        if (pool == null) {
            LOG.warnf("No process pool found for code [%s], routing message [%s] to default pool",
                message.poolCode(), message.id());

            // Add warning about missing pool
            warningService.addWarning(
                "ROUTING",
                "WARN",
                String.format("No pool found for code [%s], using default pool with concurrency %d",
                    message.poolCode(), DEFAULT_POOL_CONCURRENCY),
                "QueueManager"
            );

            // Route to default pool instead of rejecting
            pool = getOrCreateDefaultPool();
        }

        boolean submitted = pool.submit(message);
        if (!submitted) {
            LOG.warnf("Failed to submit message [%s] to pool [%s] - queue full", message.id(), message.poolCode());
            inPipelineMap.remove(message.id());
            messageCallbacks.remove(message.id());
            updateMapSizeGauges(); // Update gauges after removing from maps
            warningService.addWarning(
                "QUEUE_FULL",
                "WARN",
                String.format("Pool [%s] queue is full, message rejected", message.poolCode()),
                "QueueManager"
            );
        }

        return submitted;
    }

    @Override
    public void ack(MessagePointer message) {
        MessageCallback callback = messageCallbacks.remove(message.id());
        if (callback != null) {
            callback.ack(message);
        }
        updateMapSizeGauges();
    }

    @Override
    public void nack(MessagePointer message) {
        MessageCallback callback = messageCallbacks.remove(message.id());
        if (callback != null) {
            callback.nack(message);
        }
        updateMapSizeGauges();
    }

    private tech.flowcatalyst.messagerouter.model.MediationType determineMediatorType(String poolCode) {
        // Map pool codes to mediator types
        // Currently only HTTP is supported
        return tech.flowcatalyst.messagerouter.model.MediationType.HTTP;
    }

    /**
     * Gets or lazily creates the default pool for messages with unknown pool codes.
     * This pool acts as a fallback to prevent message loss when pool configuration is missing.
     *
     * @return the default process pool
     */
    private ProcessPool getOrCreateDefaultPool() {
        return processPools.computeIfAbsent(DEFAULT_POOL_CODE, code -> {
            int queueCapacity = Math.max(DEFAULT_POOL_CONCURRENCY * QUEUE_CAPACITY_MULTIPLIER, MIN_QUEUE_CAPACITY);

            LOG.infof("Creating default fallback pool [%s] with concurrency %d and queue capacity %d",
                DEFAULT_POOL_CODE, DEFAULT_POOL_CONCURRENCY, queueCapacity);

            tech.flowcatalyst.messagerouter.model.MediationType mediatorType = determineMediatorType(DEFAULT_POOL_CODE);
            Mediator mediator = mediatorFactory.createMediator(mediatorType);

            ProcessPool pool = new ProcessPoolImpl(
                DEFAULT_POOL_CODE,
                DEFAULT_POOL_CONCURRENCY,
                queueCapacity,
                null, // No rate limiting for default pool
                mediator,
                this,
                inPipelineMap,
                poolMetrics,
                warningService
            );

            pool.start();
            return pool;
        });
    }
}
