package tech.flowcatalyst.messagerouter.manager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    tech.flowcatalyst.messagerouter.metrics.QueueMetricsService queueMetrics;

    @Inject
    WarningService warningService;

    @Inject
    MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, MessagePointer> inPipelineMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProcessPool> processPools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QueueConsumer> queueConsumers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MessageCallback> messageCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QueueConfig> queueConfigs = new ConcurrentHashMap<>();

    // Draining resources that are being phased out asynchronously
    private final ConcurrentHashMap<String, ProcessPool> drainingPools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QueueConsumer> drainingConsumers = new ConcurrentHashMap<>();

    private volatile boolean initialized = false;
    private volatile boolean shutdownInProgress = false;

    // Gauges for monitoring map sizes to detect memory leaks
    private AtomicInteger inPipelineMapSizeGauge;
    private AtomicInteger messageCallbacksMapSizeGauge;
    private AtomicInteger activePoolCountGauge;
    private io.micrometer.core.instrument.Counter defaultPoolUsageCounter;

    /**
     * Default constructor for CDI
     */
    public QueueManager() {
        // CDI will inject dependencies
    }

    /**
     * Test-friendly constructor with dependency injection
     * Package-private to allow test access while keeping internal
     */
    QueueManager(
        MessageRouterConfigClient configClient,
        QueueConsumerFactory queueConsumerFactory,
        MediatorFactory mediatorFactory,
        tech.flowcatalyst.messagerouter.health.QueueValidationService queueValidationService,
        PoolMetricsService poolMetrics,
        tech.flowcatalyst.messagerouter.metrics.QueueMetricsService queueMetrics,
        WarningService warningService,
        MeterRegistry meterRegistry,
        boolean messageRouterEnabled,
        int maxPools,
        int poolWarningThreshold
    ) {
        this.configClient = configClient;
        this.queueConsumerFactory = queueConsumerFactory;
        this.mediatorFactory = mediatorFactory;
        this.queueValidationService = queueValidationService;
        this.poolMetrics = poolMetrics;
        this.queueMetrics = queueMetrics;
        this.warningService = warningService;
        this.meterRegistry = meterRegistry;
        this.messageRouterEnabled = messageRouterEnabled;
        this.maxPools = maxPools;
        this.poolWarningThreshold = poolWarningThreshold;
        initializeMetrics();
    }

    void onStartup(@Observes StartupEvent event) {
        initializeMetrics();
    }

    /**
     * Initialize Micrometer gauges for map size monitoring
     */
    private void initializeMetrics() {
        if (meterRegistry == null) {
            // For tests or when metrics are not available
            inPipelineMapSizeGauge = new AtomicInteger(0);
            messageCallbacksMapSizeGauge = new AtomicInteger(0);
            activePoolCountGauge = new AtomicInteger(0);
            return;
        }

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

        // Create counter for default pool usage (indicates missing pool configuration)
        defaultPoolUsageCounter = meterRegistry.counter(
            "flowcatalyst.queuemanager.defaultpool.usage",
            List.of(Tag.of("pool", DEFAULT_POOL_CODE))
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

        // FIRST: Stop all background scheduled tasks (metrics, health checks, etc.)
        shutdownInProgress = true;
        LOG.info("Shutdown flag set - all scheduled tasks will now exit");

        // Give scheduled tasks a moment to exit cleanly
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now do the actual shutdown
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
    @RunOnVirtualThread
    void checkForMapLeaks() {
        if (!initialized || shutdownInProgress) {
            // Skip check until system is initialized or during shutdown
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

    /**
     * Periodically clean up draining pools and consumers that have finished processing
     * Runs every 10 seconds to check if old resources can be cleaned up
     */
    @Scheduled(every = "10s")
    @RunOnVirtualThread
    void cleanupDrainingResources() {
        if (!initialized || shutdownInProgress) {
            return;
        }

        // Check draining pools
        for (Map.Entry<String, ProcessPool> entry : drainingPools.entrySet()) {
            String poolCode = entry.getKey();
            ProcessPool pool = entry.getValue();

            // Check if pool has finished draining (queue empty and all workers idle)
            if (pool.isFullyDrained()) {
                LOG.infof("Pool [%s] has fully drained, cleaning up resources", poolCode);
                pool.shutdown();
                drainingPools.remove(poolCode);
                poolMetrics.removePoolMetrics(poolCode);
                LOG.infof("Cleaned up draining pool [%s]", poolCode);
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debugf("Pool [%s] still draining (queue: %d, active: %d)",
                        poolCode, pool.getQueueSize(), pool.getActiveWorkers());
                }
            }
        }

        // Check draining consumers
        for (Map.Entry<String, QueueConsumer> entry : drainingConsumers.entrySet()) {
            String queueId = entry.getKey();
            QueueConsumer consumer = entry.getValue();

            // Check if consumer has finished (all threads terminated)
            if (consumer.isFullyStopped()) {
                LOG.infof("Consumer [%s] has fully stopped, cleaning up resources", queueId);
                drainingConsumers.remove(queueId);
                LOG.infof("Cleaned up draining consumer [%s]", queueId);
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debugf("Consumer [%s] still stopping", queueId);
                }
            }
        }
    }

    /**
     * Periodically monitor consumer health and restart stalled/unhealthy consumers
     * Runs every 60 seconds to detect and remediate hung consumer threads
     */
    @Scheduled(every = "60s")
    @RunOnVirtualThread
    void monitorAndRestartUnhealthyConsumers() {
        if (!initialized || shutdownInProgress) {
            return;
        }

        for (Map.Entry<String, QueueConsumer> entry : queueConsumers.entrySet()) {
            String queueIdentifier = entry.getKey();
            QueueConsumer consumer = entry.getValue();

            // Check if consumer is unhealthy (stalled/hung)
            if (!consumer.isHealthy()) {
                long lastPollTime = consumer.getLastPollTime();
                long timeSinceLastPoll = lastPollTime > 0 ?
                    (System.currentTimeMillis() - lastPollTime) / 1000 : -1;

                LOG.warnf("Consumer for queue [%s] is unhealthy (last poll %ds ago) - initiating restart",
                    queueIdentifier, timeSinceLastPoll);

                // Add warning for visibility
                warningService.addWarning(
                    "CONSUMER_RESTART",
                    "WARN",
                    String.format("Consumer for queue [%s] was unhealthy (last poll %ds ago) and has been restarted",
                        queueIdentifier, timeSinceLastPoll),
                    "QueueManager"
                );

                try {
                    // Get the queue configuration for this consumer
                    QueueConfig queueConfig = queueConfigs.get(queueIdentifier);
                    if (queueConfig == null) {
                        LOG.errorf("Cannot restart consumer [%s] - queue configuration not found", queueIdentifier);
                        continue;
                    }

                    // Stop the unhealthy consumer
                    LOG.infof("Stopping unhealthy consumer for queue [%s]", queueIdentifier);
                    consumer.stop();

                    // Move to draining for cleanup
                    queueConsumers.remove(queueIdentifier);
                    drainingConsumers.put(queueIdentifier, consumer);

                    // Calculate connections (same logic as syncConfiguration)
                    int connections = queueConfig.connections() != null
                        ? queueConfig.connections()
                        : 1; // Default to 1 if not specified

                    // Create and start new consumer
                    LOG.infof("Creating replacement consumer for queue [%s] with %d connections",
                        queueIdentifier, connections);
                    QueueConsumer newConsumer = queueConsumerFactory.createConsumer(queueConfig, connections);
                    newConsumer.start();
                    queueConsumers.put(queueIdentifier, newConsumer);

                    LOG.infof("Successfully restarted consumer for queue [%s]", queueIdentifier);

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to restart consumer for queue [%s]", queueIdentifier);
                    warningService.addWarning(
                        "CONSUMER_RESTART_FAILED",
                        "ERROR",
                        String.format("Failed to restart consumer for queue [%s]: %s",
                            queueIdentifier, e.getMessage()),
                        "QueueManager"
                    );
                }
            }
        }
    }

    @Scheduled(every = "${message-router.sync-interval:5m}", delay = 2, delayUnit = java.util.concurrent.TimeUnit.SECONDS)
    @RunOnVirtualThread
    void scheduledSync() {
        if (shutdownInProgress) {
            return;
        }

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

            // Step 1: Identify pools that need to be replaced
            // Use parallel approach: stop consuming, start new pool, drain old pool async
            Map<String, ProcessingPool> newPools = new ConcurrentHashMap<>();
            for (ProcessingPool poolConfig : config.processingPools()) {
                newPools.put(poolConfig.code(), poolConfig);
            }

            // Handle pool config changes: update in-place or drain if removed
            for (Map.Entry<String, ProcessPool> entry : processPools.entrySet()) {
                String poolCode = entry.getKey();
                ProcessPool existingPool = entry.getValue();
                ProcessingPool newPoolConfig = newPools.get(poolCode);

                if (newPoolConfig == null) {
                    // Pool removed from config: drain and remove
                    LOG.infof("Pool [%s] removed from config - draining asynchronously", poolCode);
                    processPools.remove(poolCode);
                    drainingPools.put(poolCode, existingPool);
                    updateMapSizeGauges();
                    LOG.infof("Pool [%s] moved to draining state (queue: %d, active: %d)",
                        poolCode, existingPool.getQueueSize(), existingPool.getActiveWorkers());
                } else {
                    // Pool exists in new config: update concurrency and/or rate limit in-place
                    boolean concurrencyChanged = newPoolConfig.concurrency() != existingPool.getConcurrency();
                    boolean rateLimitChanged = !java.util.Objects.equals(newPoolConfig.rateLimitPerMinute(), existingPool.getRateLimitPerMinute());

                    if (concurrencyChanged) {
                        int oldConcurrency = existingPool.getConcurrency();
                        int newConcurrency = newPoolConfig.concurrency();
                        boolean updateSuccess = existingPool.updateConcurrency(newConcurrency, 60); // 60 second timeout
                        if (updateSuccess) {
                            LOG.infof("Pool [%s] concurrency updated: %d -> %d (in-place)",
                                poolCode, oldConcurrency, newConcurrency);
                        } else {
                            LOG.warnf("Pool [%s] concurrency update timed out waiting for idle slots. " +
                                "Current concurrency: %d, target: %d, active workers: %d",
                                poolCode, oldConcurrency, newConcurrency, existingPool.getActiveWorkers());
                        }
                    }

                    if (rateLimitChanged) {
                        Integer oldLimit = existingPool.getRateLimitPerMinute();
                        Integer newLimit = newPoolConfig.rateLimitPerMinute();
                        existingPool.updateRateLimit(newLimit);
                        LOG.infof("Pool [%s] rate limit updated: %s -> %s (in-place)",
                            poolCode,
                            oldLimit != null ? oldLimit + "/min" : "none",
                            newLimit != null ? newLimit + "/min" : "none");
                    }
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

            // Step 4: Sync queue consumers using parallel approach
            // Stop old consumer -> Start new consumer -> Old consumer finishes async
            Map<String, QueueConfig> newQueues = new ConcurrentHashMap<>();
            for (QueueConfig queueConfig : config.queues()) {
                String queueIdentifier = queueConfig.queueName() != null
                    ? queueConfig.queueName()
                    : queueConfig.queueUri();
                newQueues.put(queueIdentifier, queueConfig);
            }

            // Phase out consumers for queues that no longer exist
            for (Map.Entry<String, QueueConsumer> entry : queueConsumers.entrySet()) {
                String queueIdentifier = entry.getKey();
                if (!newQueues.containsKey(queueIdentifier)) {
                    LOG.infof("Phasing out consumer for removed queue [%s]", queueIdentifier);
                    QueueConsumer consumer = entry.getValue();

                    // Stop consumer (sets running=false, initiates graceful shutdown)
                    // This stops polling immediately but lets in-flight messages complete
                    consumer.stop();

                    // Move to draining consumers for async cleanup
                    queueConsumers.remove(queueIdentifier);
                    queueConfigs.remove(queueIdentifier);
                    drainingConsumers.put(queueIdentifier, consumer);

                    LOG.infof("Consumer [%s] moved to draining state", queueIdentifier);
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
                    // Use per-queue connections if specified, otherwise default to 1
                    int connections = queueConfig.connections() != null
                        ? queueConfig.connections()
                        : 1; // Default to 1 connection per queue

                    LOG.infof("Creating new queue consumer for [%s] with %d connections",
                        queueIdentifier, connections);

                    QueueConsumer consumer = queueConsumerFactory.createConsumer(queueConfig, connections);
                    consumer.start();
                    queueConsumers.put(queueIdentifier, consumer);
                    queueConfigs.put(queueIdentifier, queueConfig);
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
        LOG.info("Stopping all queue consumers during shutdown");

        // Initiate shutdown for ALL consumers (non-blocking, fast)
        for (QueueConsumer consumer : queueConsumers.values()) {
            try {
                consumer.stop(); // Sets flag and initiates shutdown, returns immediately
                drainingConsumers.put(consumer.getQueueIdentifier(), consumer);
            } catch (Exception e) {
                LOG.errorf(e, "Error stopping consumer: %s", consumer.getQueueIdentifier());
            }
        }
        queueConsumers.clear();

        // Now wait for all consumers to finish in PARALLEL
        // Max 25s (enough for SQS 20s long poll + small buffer)
        LOG.infof("Waiting for %d consumers to finish stopping (in parallel)...", drainingConsumers.size());
        long startTime = System.currentTimeMillis();
        long maxWaitMs = 25_000; // 25 seconds for all consumers to naturally complete current polls

        while (!drainingConsumers.isEmpty() && (System.currentTimeMillis() - startTime) < maxWaitMs) {
            List<String> stoppedConsumers = new ArrayList<>();

            for (Map.Entry<String, QueueConsumer> entry : drainingConsumers.entrySet()) {
                String queueId = entry.getKey();
                QueueConsumer consumer = entry.getValue();

                if (consumer.isFullyStopped()) {
                    LOG.debugf("Consumer [%s] fully stopped during shutdown", queueId);
                    stoppedConsumers.add(queueId);
                }
            }

            // Remove stopped consumers after iteration
            for (String queueId : stoppedConsumers) {
                drainingConsumers.remove(queueId);
            }

            if (!drainingConsumers.isEmpty()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    LOG.warn("Interrupted while waiting for consumers to stop");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (!drainingConsumers.isEmpty()) {
            LOG.warnf("%d consumers did not fully stop within 25s, forcing shutdown now", drainingConsumers.size());
            drainingConsumers.clear();
        } else {
            LOG.info("All consumers stopped cleanly");
        }
    }

    private void drainAllPools() {
        LOG.info("Draining all process pools during shutdown");

        // Move all active pools to draining state
        for (ProcessPool pool : processPools.values()) {
            try {
                pool.drain();
                drainingPools.put(pool.getPoolCode(), pool);
            } catch (Exception e) {
                LOG.errorf(e, "Error draining pool: %s", pool.getPoolCode());
            }
        }
        processPools.clear();

        // Wait for all pools to finish draining (blocking during shutdown is ok)
        LOG.infof("Waiting for %d pools to finish draining...", drainingPools.size());
        long startTime = System.currentTimeMillis();
        long maxWaitMs = 60_000; // 60 seconds max wait

        while (!drainingPools.isEmpty() && (System.currentTimeMillis() - startTime) < maxWaitMs) {
            List<String> drainedPools = new ArrayList<>();

            for (Map.Entry<String, ProcessPool> entry : drainingPools.entrySet()) {
                String poolCode = entry.getKey();
                ProcessPool pool = entry.getValue();

                if (pool.isFullyDrained()) {
                    LOG.infof("Pool [%s] fully drained during shutdown", poolCode);
                    pool.shutdown();
                    drainedPools.add(poolCode);
                }
            }

            // Remove drained pools after iteration
            for (String poolCode : drainedPools) {
                drainingPools.remove(poolCode);
            }

            if (!drainingPools.isEmpty()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    LOG.warn("Interrupted while waiting for pools to drain");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Force shutdown any remaining pools
        if (!drainingPools.isEmpty()) {
            LOG.warnf("%d pools did not finish draining within %d seconds, forcing shutdown",
                drainingPools.size(), maxWaitMs / 1000);
            for (ProcessPool pool : drainingPools.values()) {
                try {
                    pool.shutdown();
                } catch (Exception e) {
                    LOG.errorf(e, "Error forcing shutdown of pool: %s", pool.getPoolCode());
                }
            }
            drainingPools.clear();
        }

        LOG.info("All pools drained");
    }

    /**
     * Called by queue consumers to route a batch of messages with batch-level policies.
     * Implements the following batch-level rules:
     * 1. Duplicate detection (individual messages)
     * 2. Pool buffer capacity check (nack all messages for full pools)
     * 3. Rate limit check (nack all messages for rate-limited pools)
     * 4. MessageGroup sequential nacking (if one message in group is nacked, nack all subsequent in batch)
     *
     * @param messages list of messages with their callbacks
     */
    public void routeMessageBatch(List<BatchMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }

        // Generate unique batch ID for FIFO tracking
        String batchId = UUID.randomUUID().toString();
        LOG.debugf("Processing batch [%s] with %d messages", batchId, messages.size());

        // Phase 1: Filter duplicates and group by pool
        Map<String, List<BatchMessage>> messagesByPool = new HashMap<>();
        List<BatchMessage> duplicates = new ArrayList<>();

        for (BatchMessage batchMsg : messages) {
            if (inPipelineMap.containsKey(batchMsg.message().id())) {
                LOG.debugf("Message [%s] already in pipeline, will nack", batchMsg.message().id());
                duplicates.add(batchMsg);
            } else {
                String poolCode = batchMsg.message().poolCode();
                messagesByPool.computeIfAbsent(poolCode, k -> new ArrayList<>()).add(batchMsg);
            }
        }

        // Nack all duplicates
        for (BatchMessage dup : duplicates) {
            dup.callback().nack(dup.message());
            queueMetrics.recordMessageProcessed(getQueueIdentifier(dup), false);
        }

        // Phase 2: Check pool buffer capacity and rate limits
        Map<String, List<BatchMessage>> messagesToRoute = new HashMap<>();
        List<BatchMessage> toNackPoolFull = new ArrayList<>();
        List<BatchMessage> toNackRateLimited = new ArrayList<>();

        for (Map.Entry<String, List<BatchMessage>> entry : messagesByPool.entrySet()) {
            String poolCode = entry.getKey();
            List<BatchMessage> poolMessages = entry.getValue();

            // Get or create pool
            ProcessPool pool = processPools.get(poolCode);
            if (pool == null) {
                LOG.warnf("No process pool found for code [%s], routing to default pool", poolCode);
                if (defaultPoolUsageCounter != null) {
                    defaultPoolUsageCounter.increment();
                }
                warningService.addWarning(
                    "ROUTING",
                    "WARN",
                    String.format("No pool found for code [%s], using default pool", poolCode),
                    "QueueManager"
                );
                pool = getOrCreateDefaultPool();
            }

            // Check if pool has capacity for ALL messages for this pool
            int availableCapacity = pool.getQueueCapacity() - pool.getQueueSize();
            if (availableCapacity < poolMessages.size()) {
                LOG.warnf("Pool [%s] buffer full - nacking all %d messages for this pool in batch",
                    poolCode, poolMessages.size());
                toNackPoolFull.addAll(poolMessages);
                warningService.addWarning(
                    "QUEUE_FULL",
                    "WARN",
                    String.format("Pool [%s] queue full, nacking %d messages from batch",
                        poolCode, poolMessages.size()),
                    "QueueManager"
                );
                continue;
            }

            // Check rate limiting for this pool
            if (pool.isRateLimited()) {
                LOG.warnf("Pool [%s] rate limited - nacking all %d messages for this pool in batch",
                    poolCode, poolMessages.size());
                toNackRateLimited.addAll(poolMessages);
                continue;
            }

            // Pool is available - add to routing list
            messagesToRoute.put(poolCode, poolMessages);
        }

        // Nack all pool-full messages
        for (BatchMessage batchMsg : toNackPoolFull) {
            batchMsg.callback().nack(batchMsg.message());
            queueMetrics.recordMessageProcessed(getQueueIdentifier(batchMsg), false);
        }

        // Nack all rate-limited messages
        for (BatchMessage batchMsg : toNackRateLimited) {
            batchMsg.callback().nack(batchMsg.message());
            queueMetrics.recordMessageProcessed(getQueueIdentifier(batchMsg), false);
        }

        // Phase 3: Route messages with messageGroup sequential nacking
        for (Map.Entry<String, List<BatchMessage>> entry : messagesToRoute.entrySet()) {
            String poolCode = entry.getKey();
            List<BatchMessage> poolMessages = entry.getValue();
            ProcessPool pool = processPools.getOrDefault(poolCode, getOrCreateDefaultPool());

            // Group by messageGroupId to enforce sequential nacking within groups
            Map<String, List<BatchMessage>> messagesByGroup = new LinkedHashMap<>();
            for (BatchMessage batchMsg : poolMessages) {
                String groupId = batchMsg.message().messageGroupId();
                if (groupId == null || groupId.isBlank()) {
                    groupId = "__DEFAULT__";
                }
                messagesByGroup.computeIfAbsent(groupId, k -> new ArrayList<>()).add(batchMsg);
            }

            // Process each group sequentially
            for (Map.Entry<String, List<BatchMessage>> groupEntry : messagesByGroup.entrySet()) {
                String groupId = groupEntry.getKey();
                List<BatchMessage> groupMessages = groupEntry.getValue();
                boolean nackRemaining = false;

                for (BatchMessage batchMsg : groupMessages) {
                    MessagePointer message = batchMsg.message();
                    MessageCallback callback = batchMsg.callback();

                    // If we're nacking remaining messages in this group, nack and continue
                    if (nackRemaining) {
                        LOG.debugf("Nacking message [%s] - previous message in group [%s] was nacked",
                            message.id(), groupId);
                        callback.nack(message);
                        queueMetrics.recordMessageProcessed(getQueueIdentifier(batchMsg), false);
                        continue;
                    }

                    // Enrich message with batchId for FIFO tracking in pool
                    MessagePointer enrichedMessage = new MessagePointer(
                        message.id(),
                        message.poolCode(),
                        message.authToken(),
                        message.mediationType(),
                        message.mediationTarget(),
                        message.messageGroupId(),
                        batchId  // Add batch ID
                    );

                    // Add to pipeline
                    inPipelineMap.put(enrichedMessage.id(), enrichedMessage);
                    messageCallbacks.put(enrichedMessage.id(), callback);

                    // Try to submit to pool
                    boolean submitted = pool.submit(enrichedMessage);
                    if (!submitted) {
                        LOG.warnf("Failed to submit message [%s] to pool [%s] - nacking this and all subsequent in group [%s]",
                            enrichedMessage.id(), poolCode, groupId);

                        // Remove from pipeline since we're nacking
                        inPipelineMap.remove(enrichedMessage.id());
                        messageCallbacks.remove(enrichedMessage.id());

                        // Nack this message
                        callback.nack(enrichedMessage);
                        queueMetrics.recordMessageProcessed(getQueueIdentifier(batchMsg), false);

                        // Set flag to nack all remaining messages in this group
                        nackRemaining = true;
                    } else {
                        LOG.debugf("Routed message [%s] to pool [%s]", enrichedMessage.id(), poolCode);
                        queueMetrics.recordMessageProcessed(getQueueIdentifier(batchMsg), true);
                    }
                }
            }
        }

        updateMapSizeGauges();
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

            // Increment default pool usage counter
            if (defaultPoolUsageCounter != null) {
                defaultPoolUsageCounter.increment();
            }

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

            // Nack the message to return it to the queue
            callback.nack(message);

            warningService.addWarning(
                "QUEUE_FULL",
                "WARN",
                String.format("Pool [%s] queue is full, message rejected", message.poolCode()),
                "QueueManager"
            );
        }

        return submitted;
    }

    /**
     * Helper method to get queue identifier from batch message
     */
    private String getQueueIdentifier(BatchMessage batchMsg) {
        // This will be provided by the consumer when creating BatchMessage
        return batchMsg.queueIdentifier();
    }

    /**
     * Record for batch message processing
     */
    public record BatchMessage(
        MessagePointer message,
        MessageCallback callback,
        String queueIdentifier
    ) {}

    @Override
    public void ack(MessagePointer message) {
        MessageCallback callback = messageCallbacks.remove(message.id());
        inPipelineMap.remove(message.id());
        if (callback != null) {
            callback.ack(message);
        }
        updateMapSizeGauges();
    }

    @Override
    public void nack(MessagePointer message) {
        MessageCallback callback = messageCallbacks.remove(message.id());
        inPipelineMap.remove(message.id());
        if (callback != null) {
            callback.nack(message);
        }
        updateMapSizeGauges();
    }

    /**
     * Gets the health status of all active queue consumers.
     *
     * @return map of queue identifier to consumer health status
     */
    public Map<String, QueueConsumerHealth> getConsumerHealthStatus() {
        Map<String, QueueConsumerHealth> healthStatus = new HashMap<>();

        // Check active consumers
        for (Map.Entry<String, QueueConsumer> entry : queueConsumers.entrySet()) {
            String queueId = entry.getKey();
            QueueConsumer consumer = entry.getValue();

            boolean isHealthy = consumer.isHealthy();
            long lastPollTime = consumer.getLastPollTime();
            long timeSinceLastPoll = lastPollTime > 0 ?
                System.currentTimeMillis() - lastPollTime : -1;

            healthStatus.put(queueId, new QueueConsumerHealth(
                queueId,
                isHealthy,
                lastPollTime,
                timeSinceLastPoll,
                !consumer.isFullyStopped()
            ));
        }

        return healthStatus;
    }

    /**
     * Simple record for consumer health status
     */
    public record QueueConsumerHealth(
        String queueIdentifier,
        boolean isHealthy,
        long lastPollTimeMs,
        long timeSinceLastPollMs,
        boolean isRunning
    ) {}

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
