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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process pool implementation with per-message-group FIFO ordering using dedicated virtual threads.
 *
 * <h2>Architecture: Per-Group Virtual Threads</h2>
 * Each message group gets its own dedicated Java 21 virtual thread that processes messages sequentially.
 * This architecture provides:
 * <ul>
 *   <li><b>FIFO within group:</b> Messages with same messageGroupId process sequentially</li>
 *   <li><b>Concurrent across groups:</b> Different messageGroupIds process in parallel</li>
 *   <li><b>Zero blocking:</b> No scanning overhead, no worker contention</li>
 *   <li><b>Auto-cleanup:</b> Idle groups cleaned up after 5 minutes</li>
 *   <li><b>Scales to 100K+ groups:</b> O(1) routing, minimal memory per group (~2KB)</li>
 * </ul>
 *
 * <p><b>Example:</b> Messages for "order-12345" and "order-67890":
 * <pre>
 * Message arrives for "order-12345":
 *   → Get or create queue + virtual thread for "order-12345"
 *   → Virtual thread blocks on queue.poll() (no CPU waste)
 *   → Processes msg1, msg2, msg3 sequentially (FIFO)
 *   → After 5 min idle, thread exits and group cleaned up
 *
 * Meanwhile, "order-67890" processes concurrently in its own virtual thread
 * </pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><b>Routing:</b> O(1) - direct queue access via ConcurrentHashMap</li>
 *   <li><b>Idle CPU:</b> 0% - virtual threads block on queue, no scanning</li>
 *   <li><b>Memory per group:</b> ~2KB (virtual thread + queue overhead)</li>
 *   <li><b>Scales to:</b> 100K+ concurrent message groups</li>
 *   <li><b>No memory leak:</b> Idle groups auto-cleaned after 5 minutes</li>
 * </ul>
 *
 * <h2>Buffer Sizing</h2>
 * Each message group gets its own queue with capacity={@code queueCapacity}:
 * <ul>
 *   <li>Total capacity: dynamic (active groups × queueCapacity)</li>
 *   <li>Per-group isolation: one group cannot starve others</li>
 *   <li>Backward compatible: messages without messageGroupId use DEFAULT_GROUP</li>
 * </ul>
 *
 * <p>Buffer capacity is typically calculated as max(concurrency × 10, 500):
 * <ul>
 *   <li>5 workers → 500 buffer per group</li>
 *   <li>100 workers → 1000 buffer per group</li>
 *   <li>200 workers → 2000 buffer per group</li>
 * </ul>
 *
 * <h2>Concurrency Control</h2>
 * Pool-level concurrency is enforced by a semaphore with {@code concurrency} permits.
 * Each message group's virtual thread must acquire a semaphore permit before processing.
 * This ensures total concurrent processing never exceeds the configured limit.
 *
 * <h2>Backpressure</h2>
 * When a group's buffer is full, messages are rejected and rely on queue visibility timeout
 * for redelivery. This allows SQS/ActiveMQ to act as overflow buffer when the system is overwhelmed.
 *
 * <p>See <a href="../../../../../MESSAGE_GROUP_FIFO.md">MESSAGE_GROUP_FIFO.md</a> for detailed documentation.
 *
 * @see MessagePointer#messageGroupId()
 * @see <a href="../../../../../MESSAGE_GROUP_FIFO.md">Message Group FIFO Ordering Documentation</a>
 */
public class ProcessPoolImpl implements ProcessPool {

    private static final Logger LOG = Logger.getLogger(ProcessPoolImpl.class);

    private final String poolCode;
    private final int concurrency;
    private final int queueCapacity;
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

    // Per-message-group queues for FIFO ordering within groups, concurrent across groups
    // Key: messageGroupId (e.g., "order-12345"), Value: Queue for that group's messages
    // Each group has its own dedicated virtual thread that blocks on queue.poll()
    private final ConcurrentHashMap<String, BlockingQueue<MessagePointer>> messageGroupQueues = new ConcurrentHashMap<>();

    // Track total messages across all group queues for metrics
    private final AtomicInteger totalQueuedMessages = new AtomicInteger(0);

    // Default group for messages without a messageGroupId (backward compatibility)
    private static final String DEFAULT_GROUP = "__DEFAULT__";

    // Idle timeout before cleaning up inactive message groups (5 minutes)
    private static final long IDLE_TIMEOUT_MINUTES = 5;

    // Batch+Group FIFO tracking: When a message in a batch+group fails, all subsequent
    // messages in that batch+group must be nacked to preserve FIFO ordering
    // Key: "batchId|messageGroupId", Value: true if this batch+group has a failed message
    private final ConcurrentHashMap<String, Boolean> failedBatchGroups = new ConcurrentHashMap<>();

    // Track remaining messages per batch+group for cleanup
    // Key: "batchId|messageGroupId", Value: count of messages still in flight
    private final ConcurrentHashMap<String, AtomicInteger> batchGroupMessageCount = new ConcurrentHashMap<>();

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
            LOG.infof("Starting process pool [%s] with concurrency %d (per-group virtual threads)", poolCode, concurrency);
            // No upfront worker threads needed - virtual threads created on-demand per message group
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

        LOG.infof("Process pool [%s] set to draining mode (queued: %d, active: %d, groups: %d)",
            poolCode, totalQueuedMessages.get(), concurrency - semaphore.availablePermits(), messageGroupQueues.size());
    }

    @Override
    public boolean submit(MessagePointer message) {
        // Route message to appropriate group queue
        String groupId = message.messageGroupId();
        if (groupId == null || groupId.isBlank()) {
            groupId = DEFAULT_GROUP;
        }

        final String finalGroupId = groupId;

        // Track this message for batch+group FIFO ordering
        String batchId = message.batchId();
        if (batchId != null && !batchId.isBlank()) {
            String batchGroupKey = batchId + "|" + finalGroupId;
            batchGroupMessageCount.computeIfAbsent(batchGroupKey, k -> new AtomicInteger(0))
                .incrementAndGet();
            LOG.debugf("Tracking message [%s] in batch+group [%s], count incremented",
                message.id(), batchGroupKey);
        }

        // Get or create queue for this message group
        // If this is a new group, also start a dedicated virtual thread for it
        BlockingQueue<MessagePointer> groupQueue = messageGroupQueues.computeIfAbsent(
            groupId,
            k -> {
                LinkedBlockingQueue<MessagePointer> queue = new LinkedBlockingQueue<>(queueCapacity);
                // Start dedicated virtual thread for this message group
                executorService.submit(() -> processMessageGroup(finalGroupId, queue));
                LOG.debugf("Created new message group [%s] with dedicated virtual thread", finalGroupId);
                return queue;
            }
        );

        // Offer message to group queue
        boolean submitted = groupQueue.offer(message);
        if (submitted) {
            totalQueuedMessages.incrementAndGet();
            poolMetrics.recordMessageSubmitted(poolCode);
            updateGauges();
        } else {
            // Failed to submit - decrement batch+group count
            if (batchId != null && !batchId.isBlank()) {
                String batchGroupKey = batchId + "|" + finalGroupId;
                decrementAndCleanupBatchGroup(batchGroupKey);
            }
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
        return totalQueuedMessages.get() == 0 && semaphore.availablePermits() == concurrency;
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
        return totalQueuedMessages.get();
    }

    @Override
    public int getActiveWorkers() {
        return concurrency - semaphore.availablePermits();
    }

    @Override
    public int getQueueCapacity() {
        return queueCapacity;
    }

    @Override
    public boolean isRateLimited() {
        if (rateLimiter == null) {
            return false;
        }
        // Check available permissions without consuming a permit
        // If no permissions available, the pool is currently rate limited
        return rateLimiter.getMetrics().getAvailablePermissions() <= 0;
    }

    /**
     * Process messages for a single message group.
     * This method runs in its own dedicated virtual thread per group.
     * Blocks on queue.poll() with timeout, auto-cleans up after idle period.
     *
     * @param groupId the message group ID
     * @param queue the queue for this message group
     */
    private void processMessageGroup(String groupId, BlockingQueue<MessagePointer> queue) {
        LOG.debugf("Starting message group processor for [%s]", groupId);

        try {
            while (running.get()) {
                MessagePointer message = null;
                String messageId = null;
                boolean semaphoreAcquired = false;

                try {
                    // 1. Block waiting for message with idle timeout
                    // This is efficient with virtual threads - no CPU waste
                    message = queue.poll(IDLE_TIMEOUT_MINUTES, TimeUnit.MINUTES);

                    if (message == null) {
                        // Idle timeout - check if queue is still empty and cleanup if so
                        if (queue.isEmpty()) {
                            LOG.debugf("Message group [%s] idle for %d minutes, cleaning up",
                                groupId, IDLE_TIMEOUT_MINUTES);
                            messageGroupQueues.remove(groupId);
                            return; // Exit this virtual thread
                        }
                        continue; // Queue became non-empty, keep processing
                    }

                    // We have a message!
                    totalQueuedMessages.decrementAndGet();
                    messageId = message.id();

                    // 2. Set MDC context for structured logging
                    setMDCContext(message);

                    // 3. Check if batch+group has already failed (FIFO enforcement)
                    // If a previous message in this batch+group failed, nack all subsequent messages
                    String batchId = message.batchId();
                    String messageGroupId = message.messageGroupId() != null ? message.messageGroupId() : DEFAULT_GROUP;
                    String batchGroupKey = batchId != null ? batchId + "|" + messageGroupId : null;

                    if (batchGroupKey != null && failedBatchGroups.containsKey(batchGroupKey)) {
                        LOG.warnf("Message [%s] from failed batch+group [%s], nacking to preserve FIFO ordering",
                            message.id(), batchGroupKey);
                        nackSafely(message);
                        decrementAndCleanupBatchGroup(batchGroupKey);
                        updateGauges(); // Update gauges since we polled from queue
                        continue; // Skip to next message
                    }

                    // 4. Check rate limiting BEFORE acquiring semaphore
                    // This prevents rate-limited messages from blocking concurrency slots
                    if (shouldRateLimit(message)) {
                        LOG.warn("Rate limit exceeded, nacking message without acquiring semaphore");
                        poolMetrics.recordRateLimitExceeded(poolCode);

                        // Fast-fail: Set 1-second visibility for quick retry if supported
                        if (messageCallback instanceof tech.flowcatalyst.messagerouter.callback.MessageVisibilityControl visibilityControl) {
                            visibilityControl.setFastFailVisibility(message);
                        }

                        nackSafely(message);

                        // Decrement batch+group count on rate limit nack
                        if (batchGroupKey != null) {
                            decrementAndCleanupBatchGroup(batchGroupKey);
                        }

                        updateGauges(); // Update gauges since we polled from queue
                        continue; // Don't acquire semaphore, move to next message
                    }

                    // 5. Acquire semaphore permit for pool-level concurrency control
                    // This ensures we don't exceed the configured concurrency limit
                    semaphore.acquire();
                    semaphoreAcquired = true;

                    // 6. Update gauges to reflect semaphore acquisition
                    updateGauges();

                    // 7. Process message through mediator
                    long startTime = System.currentTimeMillis();
                    MediationResult result = mediator.process(message);
                    long durationMs = System.currentTimeMillis() - startTime;

                    // 8. Handle mediation result
                    handleMediationResult(message, result, durationMs);

                } catch (InterruptedException e) {
                    LOG.warnf("Message group processor [%s] interrupted, exiting gracefully", groupId);
                    Thread.currentThread().interrupt();
                    // Nack message if we have one
                    if (message != null) {
                        nackSafely(message);

                        // Decrement batch+group count on interruption
                        String intBatchId = message.batchId();
                        if (intBatchId != null && !intBatchId.isBlank()) {
                            String intMessageGroupId = message.messageGroupId() != null ? message.messageGroupId() : DEFAULT_GROUP;
                            String intBatchGroupKey = intBatchId + "|" + intMessageGroupId;
                            decrementAndCleanupBatchGroup(intBatchGroupKey);
                        }
                    }
                    break; // Exit thread

                } catch (Exception e) {
                    LOG.errorf(e, "Unexpected error processing message in group [%s]", groupId);
                    logExceptionContext(message, e);

                    // Nack message if we have one
                    if (message != null) {
                        nackSafely(message);
                        recordProcessingError(message, e);

                        // Decrement batch+group count on exception
                        String exBatchId = message.batchId();
                        if (exBatchId != null && !exBatchId.isBlank()) {
                            String exMessageGroupId = message.messageGroupId() != null ? message.messageGroupId() : DEFAULT_GROUP;
                            String exBatchGroupKey = exBatchId + "|" + exMessageGroupId;
                            decrementAndCleanupBatchGroup(exBatchGroupKey);
                        }
                    }

                } finally {
                    // CRITICAL: Cleanup always happens here, regardless of exception path
                    performCleanup(messageId, semaphoreAcquired);
                }
            }
        } finally {
            // Final cleanup when thread exits
            LOG.debugf("Message group processor [%s] exiting", groupId);
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

        // Get batch+group key for FIFO tracking
        String batchId = message.batchId();
        String messageGroupId = message.messageGroupId() != null ? message.messageGroupId() : DEFAULT_GROUP;
        String batchGroupKey = batchId != null ? batchId + "|" + messageGroupId : null;

        if (result == MediationResult.SUCCESS) {
            LOG.infof("Message processed successfully");
            poolMetrics.recordProcessingSuccess(poolCode, durationMs);
            messageCallback.ack(message);

            // Decrement batch+group count on success
            if (batchGroupKey != null) {
                decrementAndCleanupBatchGroup(batchGroupKey);
            }
        } else if (result == MediationResult.ERROR_CONFIG) {
            // Configuration error (404, etc) - ACK to prevent infinite retries
            LOG.errorf("Configuration error - ACKing message to prevent retry: %s", result);
            poolMetrics.recordProcessingFailure(poolCode, durationMs, result.name());
            messageCallback.ack(message);

            // Decrement batch+group count (config error is treated as ack)
            if (batchGroupKey != null) {
                decrementAndCleanupBatchGroup(batchGroupKey);
            }

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

            // Mark batch+group as failed to trigger cascading nacks
            if (batchGroupKey != null) {
                boolean wasAlreadyFailed = failedBatchGroups.putIfAbsent(batchGroupKey, Boolean.TRUE) != null;
                if (!wasAlreadyFailed) {
                    LOG.warnf("Batch+group [%s] marked as failed - all remaining messages in this batch+group will be nacked",
                        batchGroupKey);
                }
                decrementAndCleanupBatchGroup(batchGroupKey);
            }

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
     * NOTE: inPipelineMap removal is handled by QueueManager.ack()/nack()
     */
    private void performCleanup(String messageId, boolean semaphoreAcquired) {
        try {
            // 1. Release semaphore permit (prevents permit leak)
            if (semaphoreAcquired) {
                semaphore.release();
            }

            // 2. Update gauges and clear MDC
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
        int queueSize = totalQueuedMessages.get();
        int messageGroupCount = messageGroupQueues.size();
        poolMetrics.updatePoolGauges(poolCode, activeWorkers, availablePermits, queueSize, messageGroupCount);
    }

    /**
     * Decrement the message count for a batch+group and clean up tracking maps when count reaches zero.
     * This method is called when:
     * - A message is successfully processed
     * - A message fails and is nacked
     * - A message submission fails
     *
     * @param batchGroupKey the batch+group key in format "batchId|messageGroupId"
     */
    private void decrementAndCleanupBatchGroup(String batchGroupKey) {
        AtomicInteger counter = batchGroupMessageCount.get(batchGroupKey);
        if (counter != null) {
            int remaining = counter.decrementAndGet();
            LOG.debugf("Batch+group [%s] count decremented, remaining: %d", batchGroupKey, remaining);

            if (remaining <= 0) {
                // All messages in this batch+group have been processed
                // Clean up both tracking maps
                batchGroupMessageCount.remove(batchGroupKey);
                failedBatchGroups.remove(batchGroupKey);
                LOG.debugf("Batch+group [%s] fully processed, cleaned up tracking maps", batchGroupKey);
            }
        }
    }
}
