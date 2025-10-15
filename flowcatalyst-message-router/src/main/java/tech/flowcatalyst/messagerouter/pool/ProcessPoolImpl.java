package tech.flowcatalyst.messagerouter.pool;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.mediator.Mediator;
import tech.flowcatalyst.messagerouter.metrics.PoolMetricsService;
import tech.flowcatalyst.messagerouter.model.MediationResult;
import tech.flowcatalyst.messagerouter.model.MessagePointer;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process pool implementation with configurable concurrency and dynamic buffer sizing.
 *
 * <p>Buffer capacity is calculated as max(concurrency × 10, 500) to scale with processing capacity:
 * <ul>
 *   <li>5 workers → 500 buffer (minimum applies)</li>
 *   <li>100 workers → 1000 buffer (10× scaling)</li>
 *   <li>200 workers → 2000 buffer (10× scaling)</li>
 * </ul>
 *
 * <p>When the buffer is full, messages are rejected and rely on queue visibility timeout for redelivery.
 * This allows SQS/ActiveMQ to act as overflow buffer when the system is overwhelmed.
 */
public class ProcessPoolImpl implements ProcessPool {

    private static final Logger LOG = Logger.getLogger(ProcessPoolImpl.class);

    private final String poolCode;
    private final int concurrency;
    private final int queueCapacity;
    private final BlockingQueue<MessagePointer> messageQueue;
    private final Semaphore semaphore;
    private final ExecutorService executorService;
    private final ScheduledExecutorService gaugeUpdater;
    private ScheduledFuture<?> gaugeUpdateTask;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final RateLimiter rateLimiter;  // Pool-level rate limiter (null if not configured)
    private final Mediator mediator;
    private final MessageCallback messageCallback;
    private final ConcurrentMap<String, MessagePointer> inPipelineMap;
    private final PoolMetricsService poolMetrics;
    private final WarningService warningService;

    /**
     * Creates a new process pool.
     *
     * @param poolCode unique identifier for this pool
     * @param concurrency number of concurrent workers
     * @param queueCapacity blocking queue capacity (should be max(concurrency × 10, 500))
     * @param rateLimitPerMinute optional pool-level rate limit (null if not configured)
     * @param mediator mediator for processing messages
     * @param messageCallback callback for ack/nack operations
     * @param inPipelineMap shared map for message deduplication
     * @param poolMetrics metrics service for recording pool statistics
     * @param warningService service for recording warnings
     */
    public ProcessPoolImpl(
            String poolCode,
            int concurrency,
            int queueCapacity,
            Integer rateLimitPerMinute,
            Mediator mediator,
            MessageCallback messageCallback,
            ConcurrentMap<String, MessagePointer> inPipelineMap,
            PoolMetricsService poolMetrics,
            WarningService warningService) {
        this.poolCode = poolCode;
        this.concurrency = concurrency;
        this.queueCapacity = queueCapacity;
        this.messageQueue = new ArrayBlockingQueue<>(queueCapacity);
        this.semaphore = new Semaphore(concurrency);
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.gaugeUpdater = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gauge-updater-" + poolCode);
            t.setDaemon(true);
            return t;
        });
        this.mediator = mediator;
        this.messageCallback = messageCallback;
        this.inPipelineMap = inPipelineMap;
        this.poolMetrics = poolMetrics;
        this.warningService = warningService;

        // Initialize pool capacity metrics
        poolMetrics.initializePoolCapacity(poolCode, concurrency, queueCapacity);

        // Create pool-level rate limiter if configured
        if (rateLimitPerMinute != null && rateLimitPerMinute > 0) {
            LOG.infof("Creating pool-level rate limiter for [%s] with limit %d/min", poolCode, rateLimitPerMinute);
            this.rateLimiter = RateLimiter.of(
                "pool-" + poolCode,
                RateLimiterConfig.custom()
                    .limitRefreshPeriod(Duration.ofMinutes(1))
                    .limitForPeriod(rateLimitPerMinute)
                    .timeoutDuration(Duration.ZERO)
                    .build()
            );
        } else {
            this.rateLimiter = null;
            LOG.infof("No rate limiting configured for pool [%s]", poolCode);
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            LOG.infof("Starting process pool [%s] with concurrency %d", poolCode, concurrency);
            // Start worker threads
            for (int i = 0; i < concurrency; i++) {
                executorService.submit(this::processMessages);
            }
            // Start periodic gauge updates (every 500ms for responsive metrics)
            gaugeUpdateTask = gaugeUpdater.scheduleAtFixedRate(
                this::updateGauges,
                0,
                500,
                TimeUnit.MILLISECONDS
            );
        }
    }

    @Override
    public void drain() {
        LOG.infof("Draining process pool [%s] - will finish processing buffered messages asynchronously", poolCode);
        running.set(false);

        // Non-blocking drain: Just stop accepting new work
        // The pool will continue processing messages already in its queue
        // The cleanup scheduled task will call shutdown() when fully drained

        LOG.infof("Process pool [%s] set to draining mode (queue: %d, active: %d)",
            poolCode, messageQueue.size(), concurrency - semaphore.availablePermits());
    }

    @Override
    public boolean submit(MessagePointer message) {
        boolean submitted = messageQueue.offer(message);
        if (submitted) {
            poolMetrics.recordMessageSubmitted(poolCode);
            updateGauges();
        }
        return submitted;
    }

    @Override
    public String getPoolCode() {
        return poolCode;
    }

    @Override
    public int getConcurrency() {
        return concurrency;
    }

    @Override
    public Integer getRateLimitPerMinute() {
        return rateLimiter != null ?
            rateLimiter.getRateLimiterConfig().getLimitForPeriod() : null;
    }

    @Override
    public boolean isFullyDrained() {
        return messageQueue.isEmpty() && semaphore.availablePermits() == concurrency;
    }

    @Override
    public void shutdown() {
        // Stop gauge updates first
        if (gaugeUpdateTask != null) {
            gaugeUpdateTask.cancel(false);
        }
        gaugeUpdater.shutdown();

        // Then shutdown worker executor
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warnf("Executor service for pool [%s] did not terminate within 10 seconds", poolCode);
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int getQueueSize() {
        return messageQueue.size();
    }

    @Override
    public int getActiveWorkers() {
        return concurrency - semaphore.availablePermits();
    }

    private void processMessages() {
        while (running.get() || !messageQueue.isEmpty()) {
            // Resource tracking flags - reset for each message
            MessagePointer message = null;
            String messageId = null;
            boolean semaphoreAcquired = false;

            try {
                // 1. Poll message from queue
                message = messageQueue.poll(1, TimeUnit.SECONDS);
                if (message == null) {
                    continue;
                }
                messageId = message.id();

                // 2. Set MDC context for structured logging
                setMDCContext(message);

                // 3. Check rate limiting BEFORE acquiring semaphore
                // This prevents rate-limited messages from blocking concurrency slots
                if (shouldRateLimit(message)) {
                    LOG.warn("Rate limit exceeded, nacking message without acquiring semaphore");
                    poolMetrics.recordRateLimitExceeded(poolCode);

                    // Fast-fail: Set 1-second visibility for quick retry if supported
                    if (messageCallback instanceof tech.flowcatalyst.messagerouter.callback.MessageVisibilityControl visibilityControl) {
                        visibilityControl.setFastFailVisibility(message);
                    }

                    nackSafely(message);
                    updateGauges(); // Update gauges since we polled from queue
                    continue; // Don't acquire semaphore, move to next message
                }

                // 4. Acquire semaphore permit for concurrency control
                // Only acquired if we're actually going to process the message
                semaphore.acquire();
                semaphoreAcquired = true;

                // 5. Update gauges to reflect semaphore acquisition
                updateGauges();

                // 6. Process message through mediator
                long startTime = System.currentTimeMillis();
                MediationResult result = mediator.process(message);
                long durationMs = System.currentTimeMillis() - startTime;

                // 7. Handle mediation result
                handleMediationResult(message, result, durationMs);

            } catch (InterruptedException e) {
                LOG.warn("Worker thread interrupted, exiting gracefully");
                Thread.currentThread().interrupt();
                // Nack message if we have one
                if (message != null) {
                    nackSafely(message);
                }
                break;

            } catch (Exception e) {
                LOG.error("Unexpected error processing message", e);
                logExceptionContext(message, e);

                // Nack message if we have one
                if (message != null) {
                    nackSafely(message);
                    recordProcessingError(message, e);
                }

            } finally {
                // CRITICAL: Cleanup always happens here, regardless of exception path
                performCleanup(messageId, semaphoreAcquired);
            }
        }
    }

    /**
     * Set MDC context for structured logging
     */
    private void setMDCContext(MessagePointer message) {
        MDC.put("messageId", message.id());
        MDC.put("poolCode", poolCode);
        MDC.put("mediationType", message.mediationType().toString());
        MDC.put("targetUri", message.mediationTarget());
    }

    /**
     * Check if message should be rate limited using pool-level rate limiter
     */
    private boolean shouldRateLimit(MessagePointer message) {
        // Use pool-level rate limiter (null if rate limiting not configured for this pool)
        if (rateLimiter == null) {
            return false;
        }
        return !rateLimiter.acquirePermission();
    }

    /**
     * Handle the result of message mediation
     */
    private void handleMediationResult(MessagePointer message, MediationResult result, long durationMs) {
        // Defensive: mediator should never return null, but guard against it
        if (result == null) {
            LOG.errorf("CRITICAL: Mediator returned null result for message [%s], treating as server error", message.id());
            result = MediationResult.ERROR_SERVER;
            warningService.addWarning(
                "MEDIATOR_NULL_RESULT",
                "CRITICAL",
                "Mediator returned null result for message " + message.id(),
                "ProcessPool:" + poolCode
            );
        }

        MDC.put("result", result.name());
        MDC.put("durationMs", String.valueOf(durationMs));

        if (result == MediationResult.SUCCESS) {
            LOG.infof("Message processed successfully");
            poolMetrics.recordProcessingSuccess(poolCode, durationMs);
            messageCallback.ack(message);
        } else if (result == MediationResult.ERROR_CONFIG) {
            // Configuration error (404, etc) - ACK to prevent infinite retries
            LOG.errorf("Configuration error - ACKing message to prevent retry: %s", result);
            poolMetrics.recordProcessingFailure(poolCode, durationMs, result.name());
            messageCallback.ack(message);

            // Generate CRITICAL warning for configuration error
            warningService.addWarning(
                "CONFIGURATION",
                "CRITICAL",
                String.format("Endpoint configuration error for message %s: %s - Target: %s",
                    message.id(), result, message.mediationTarget()),
                "ProcessPool:" + poolCode
            );
        } else {
            LOG.warnf("Mediation failed with result: %s", result);
            poolMetrics.recordProcessingFailure(poolCode, durationMs, result.name());

            // Real processing failure: Reset visibility to default (30s retry)
            if (messageCallback instanceof tech.flowcatalyst.messagerouter.callback.MessageVisibilityControl visibilityControl) {
                visibilityControl.resetVisibilityToDefault(message);
            }

            messageCallback.nack(message);

            // Generate warning for failed mediation
            warningService.addWarning(
                "MEDIATION",
                "ERROR",
                String.format("Mediation failed for message %s: %s", message.id(), result),
                "ProcessPool:" + poolCode
            );
        }
    }

    /**
     * Safely nack a message, catching any exceptions
     */
    private void nackSafely(MessagePointer message) {
        try {
            messageCallback.nack(message);
        } catch (Exception e) {
            LOG.errorf(e, "Error nacking message during exception handling: %s", message.id());
        }
    }

    /**
     * Log exception context for diagnostics
     */
    private void logExceptionContext(MessagePointer message, Exception e) {
        warningService.addWarning(
            "PROCESSING",
            "ERROR",
            String.format("Unexpected error processing message %s: %s",
                message != null ? message.id() : "unknown",
                e.getMessage()),
            "ProcessPool:" + poolCode
        );
    }

    /**
     * Record processing error in metrics
     */
    private void recordProcessingError(MessagePointer message, Exception e) {
        poolMetrics.recordProcessingFailure(
            poolCode,
            0,  // No duration for exceptions
            "EXCEPTION_" + e.getClass().getSimpleName()
        );
    }

    /**
     * Perform cleanup of all resources
     * This method is called in the finally block and must NEVER throw exceptions
     */
    private void performCleanup(String messageId, boolean semaphoreAcquired) {
        try {
            // 1. Remove message from pipeline map (prevents memory leak)
            if (messageId != null) {
                MessagePointer removed = inPipelineMap.remove(messageId);
                if (removed == null && LOG.isDebugEnabled()) {
                    LOG.debugf("Message %s was not in pipeline map during cleanup", messageId);
                }
            }

            // 2. Release semaphore permit (prevents permit leak)
            if (semaphoreAcquired) {
                semaphore.release();
            }

            // 3. Update gauges and clear MDC
            if (semaphoreAcquired) {
                updateGauges();
            }
            MDC.clear();

        } catch (Exception e) {
            // Cleanup should NEVER throw, but log if it does
            LOG.errorf(e, "CRITICAL: Error during cleanup for message: %s", messageId);
        }
    }

    private void updateGauges() {
        int activeWorkers = concurrency - semaphore.availablePermits();
        int availablePermits = semaphore.availablePermits();
        int queueSize = messageQueue.size();
        poolMetrics.updatePoolGauges(poolCode, activeWorkers, availablePermits, queueSize);
    }
}
