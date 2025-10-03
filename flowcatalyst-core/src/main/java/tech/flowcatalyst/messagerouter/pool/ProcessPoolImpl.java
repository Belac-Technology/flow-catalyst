package tech.flowcatalyst.messagerouter.pool;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
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

public class ProcessPoolImpl implements ProcessPool {

    private static final Logger LOG = Logger.getLogger(ProcessPoolImpl.class);

    private final String poolCode;
    private final int concurrency;
    private final int queueCapacity;
    private final BlockingQueue<MessagePointer> messageQueue;
    private final Semaphore semaphore;
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final RateLimiterRegistry rateLimiterRegistry;
    private final Mediator mediator;
    private final MessageCallback messageCallback;
    private final ConcurrentMap<String, MessagePointer> inPipelineMap;
    private final PoolMetricsService poolMetrics;
    private final WarningService warningService;

    public ProcessPoolImpl(
            String poolCode,
            int concurrency,
            int queueCapacity,
            Mediator mediator,
            MessageCallback messageCallback,
            ConcurrentMap<String, MessagePointer> inPipelineMap,
            PoolMetricsService poolMetrics,
            WarningService warningService) {
        this.poolCode = poolCode;
        this.concurrency = concurrency;
        this.queueCapacity = queueCapacity;
        this.messageQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.semaphore = new Semaphore(concurrency);
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.mediator = mediator;
        this.messageCallback = messageCallback;
        this.inPipelineMap = inPipelineMap;
        this.rateLimiterRegistry = RateLimiterRegistry.ofDefaults();
        this.poolMetrics = poolMetrics;
        this.warningService = warningService;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            LOG.infof("Starting process pool [%s] with concurrency %d", poolCode, concurrency);
            // Start worker threads
            for (int i = 0; i < concurrency; i++) {
                executorService.submit(this::processMessages);
            }
        }
    }

    @Override
    public void drain() {
        LOG.infof("Draining process pool [%s]", poolCode);
        running.set(false);

        // Wait for queue to drain and all workers to finish
        while (!messageQueue.isEmpty() || semaphore.availablePermits() < concurrency) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOG.infof("Process pool [%s] drained", poolCode);
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

    private void processMessages() {
        while (running.get() || !messageQueue.isEmpty()) {
            try {
                MessagePointer message = messageQueue.poll(1, TimeUnit.SECONDS);
                if (message == null) {
                    continue;
                }

                // Set MDC context for structured logging
                MDC.put("messageId", message.id());
                MDC.put("poolCode", poolCode);
                MDC.put("mediationType", message.mediationType());
                MDC.put("targetUri", message.mediationTarget());
                if (message.rateLimitKey() != null) {
                    MDC.put("rateLimitKey", message.rateLimitKey());
                    MDC.put("rateLimitPerMinute", message.rateLimitPerMinute());
                }

                long startTime = System.currentTimeMillis();

                // Acquire semaphore permit for concurrency control
                semaphore.acquire();
                poolMetrics.recordProcessingStarted(poolCode);
                updateGauges();

                try {
                    LOG.infof("Processing message");

                    // Check rate limiting
                    if (message.rateLimitPerMinute() != null && message.rateLimitKey() != null) {
                        RateLimiter rateLimiter = getRateLimiter(message.rateLimitKey(), message.rateLimitPerMinute());
                        if (!rateLimiter.acquirePermission()) {
                            LOG.warn("Rate limit exceeded, nacking message");
                            poolMetrics.recordRateLimitExceeded(poolCode);
                            messageCallback.nack(message);
                            continue;
                        }
                    }

                    // Process message through mediator
                    MediationResult result = mediator.process(message);
                    long durationMs = System.currentTimeMillis() - startTime;

                    MDC.put("result", result.name());
                    MDC.put("durationMs", durationMs);

                    // Handle result
                    if (result == MediationResult.SUCCESS) {
                        LOG.infof("Message processed successfully");
                        poolMetrics.recordProcessingSuccess(poolCode, durationMs);
                        messageCallback.ack(message);
                    } else {
                        LOG.warnf("Mediation failed");
                        String errorType = result.name();
                        poolMetrics.recordProcessingFailure(poolCode, durationMs, errorType);
                        messageCallback.nack(message);

                        // Generate warning for failed mediation
                        warningService.addWarning(
                            "MEDIATION",
                            "ERROR",
                            String.format("Mediation failed for message %s: %s", message.id(), errorType),
                            "ProcessPool:" + poolCode
                        );
                    }
                } finally {
                    // Remove from pipeline map
                    inPipelineMap.remove(message.id());
                    // Release semaphore permit
                    semaphore.release();
                    updateGauges();
                    // Clear MDC
                    MDC.clear();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error("Error processing message", e);
                warningService.addWarning(
                    "PROCESSING",
                    "ERROR",
                    "Unexpected error in message processing: " + e.getMessage(),
                    "ProcessPool:" + poolCode
                );
                MDC.clear();
            }
        }
    }

    private RateLimiter getRateLimiter(String key, int limitPerMinute) {
        return rateLimiterRegistry.rateLimiter(
            "rate-limiter-" + key,
            RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .limitForPeriod(limitPerMinute)
                .timeoutDuration(Duration.ZERO)
                .build()
        );
    }

    private void updateGauges() {
        int activeWorkers = concurrency - semaphore.availablePermits();
        int availablePermits = semaphore.availablePermits();
        int queueSize = messageQueue.size();
        poolMetrics.updatePoolGauges(poolCode, activeWorkers, availablePermits, queueSize);
    }
}
