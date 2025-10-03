package tech.flowcatalyst.messagerouter.manager;

import io.quarkus.runtime.ShutdownEvent;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class QueueManager implements MessageCallback {

    private static final Logger LOG = Logger.getLogger(QueueManager.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;

    @ConfigProperty(name = "message-router.enabled", defaultValue = "true")
    boolean messageRouterEnabled;

    @Inject
    @RestClient
    MessageRouterConfigClient configClient;

    @Inject
    QueueConsumerFactory queueConsumerFactory;

    @Inject
    MediatorFactory mediatorFactory;

    @Inject
    PoolMetricsService poolMetrics;

    @Inject
    WarningService warningService;

    private final ConcurrentHashMap<String, MessagePointer> inPipelineMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProcessPool> processPools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, QueueConsumer> queueConsumers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MessageCallback> messageCallbacks = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    void onShutdown(@Observes ShutdownEvent event) {
        LOG.info("QueueManager shutting down...");
        stopAllConsumers();
        drainAllPools();
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

        if (!initialized) {
            LOG.info("QueueManager initializing on first scheduled sync...");
            initialized = true;
        } else {
            LOG.info("Running scheduled configuration sync");
        }
        syncConfiguration();
    }

    private synchronized void syncConfiguration() {
        // Retry logic for initial startup
        MessageRouterConfig config = null;
        int attempts = 0;
        int maxAttempts = 3;

        while (config == null && attempts < maxAttempts) {
            try {
                attempts++;
                LOG.infof("Fetching queue configuration (attempt %d/%d)...", attempts, maxAttempts);
                config = configClient.getQueueConfig();
            } catch (Exception e) {
                if (attempts >= maxAttempts) {
                    LOG.error("Failed to fetch configuration after " + maxAttempts + " attempts", e);
                    return;
                }
                LOG.warnf("Failed to fetch config (attempt %d/%d), retrying in 2 seconds: %s",
                    attempts, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (config == null) {
            LOG.error("Configuration is null, skipping sync");
            return;
        }

        try {

            // Step 1: Stop all queue consumers
            stopAllConsumers();

            // Step 2: Identify pools to stop and start
            Map<String, ProcessingPool> newPools = new ConcurrentHashMap<>();
            for (ProcessingPool poolConfig : config.processingPools()) {
                newPools.put(poolConfig.code(), poolConfig);
            }

            // Stop and drain pools that no longer exist or have changed concurrency
            for (Map.Entry<String, ProcessPool> entry : processPools.entrySet()) {
                String poolCode = entry.getKey();
                ProcessPool existingPool = entry.getValue();
                ProcessingPool newPoolConfig = newPools.get(poolCode);

                if (newPoolConfig == null || newPoolConfig.concurrency() != existingPool.getConcurrency()) {
                    LOG.infof("Stopping and draining pool [%s]", poolCode);
                    existingPool.drain();
                    processPools.remove(poolCode);
                }
            }

            // Step 3: Start new or updated pools
            for (ProcessingPool poolConfig : config.processingPools()) {
                if (!processPools.containsKey(poolConfig.code())) {
                    LOG.infof("Creating new process pool [%s] with concurrency %d",
                        poolConfig.code(), poolConfig.concurrency());

                    // Determine mediator type based on pool code
                    String mediatorType = determineMediatorType(poolConfig.code());
                    Mediator mediator = mediatorFactory.createMediator(mediatorType);

                    ProcessPool pool = new ProcessPoolImpl(
                        poolConfig.code(),
                        poolConfig.concurrency(),
                        DEFAULT_QUEUE_CAPACITY,
                        mediator,
                        this,
                        inPipelineMap,
                        poolMetrics,
                        warningService
                    );

                    pool.start();
                    processPools.put(poolConfig.code(), pool);
                }
            }

            // Step 4: Start new queue consumers
            queueConsumers.clear();
            for (QueueConfig queueConfig : config.queues()) {
                String queueIdentifier = queueConfig.queueName() != null
                    ? queueConfig.queueName()
                    : queueConfig.queueUri();

                LOG.infof("Creating queue consumer for [%s] with %d connections",
                    queueIdentifier, config.connections());

                QueueConsumer consumer = queueConsumerFactory.createConsumer(queueConfig, config.connections());
                consumer.start();
                queueConsumers.put(queueIdentifier, consumer);
            }

            LOG.info("Configuration sync completed successfully");

        } catch (Exception e) {
            LOG.error("Failed to sync configuration", e);
        }
    }

    private void stopAllConsumers() {
        LOG.info("Stopping all queue consumers");
        for (QueueConsumer consumer : queueConsumers.values()) {
            try {
                consumer.stop();
            } catch (Exception e) {
                LOG.error("Error stopping consumer: " + consumer.getQueueIdentifier(), e);
            }
        }
    }

    private void drainAllPools() {
        LOG.info("Draining all process pools");
        for (ProcessPool pool : processPools.values()) {
            try {
                pool.drain();
            } catch (Exception e) {
                LOG.error("Error draining pool: " + pool.getPoolCode(), e);
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

        // Route to appropriate process pool
        ProcessPool pool = processPools.get(message.poolCode());
        if (pool == null) {
            LOG.errorf("No process pool found for code [%s], message [%s]", message.poolCode(), message.id());
            inPipelineMap.remove(message.id());
            messageCallbacks.remove(message.id());
            warningService.addWarning(
                "ROUTING",
                "ERROR",
                String.format("No process pool found for code [%s]", message.poolCode()),
                "QueueManager"
            );
            return false;
        }

        boolean submitted = pool.submit(message);
        if (!submitted) {
            LOG.warnf("Failed to submit message [%s] to pool [%s] - queue full", message.id(), message.poolCode());
            inPipelineMap.remove(message.id());
            messageCallbacks.remove(message.id());
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
    }

    @Override
    public void nack(MessagePointer message) {
        MessageCallback callback = messageCallbacks.remove(message.id());
        if (callback != null) {
            callback.nack(message);
        }
    }

    private String determineMediatorType(String poolCode) {
        // Map pool codes to mediator types
        return switch (poolCode) {
            case "DISPATCH-POOL" -> "DISPATCH_JOB";
            default -> "HTTP";
        };
    }
}
