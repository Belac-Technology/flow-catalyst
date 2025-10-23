package tech.flowcatalyst.messagerouter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.flowcatalyst.messagerouter.model.PoolStats;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class MicrometerPoolMetricsService implements PoolMetricsService {

    private static final Logger LOG = Logger.getLogger(MicrometerPoolMetricsService.class);

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    tech.flowcatalyst.messagerouter.warning.WarningService warningService;

    private final Map<String, PoolMetricsHolder> poolMetrics = new ConcurrentHashMap<>();

    @Override
    public void recordMessageSubmitted(String poolCode) {
        PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
        metrics.messagesSubmitted.increment();
    }

    @Override
    public void recordProcessingStarted(String poolCode) {
        // No-op: activeWorkers is now tracked directly via updatePoolGauges()
        // which calculates it from semaphore state to avoid race conditions
    }

    @Override
    public void recordProcessingFinished(String poolCode) {
        // No-op: activeWorkers is now tracked directly via updatePoolGauges()
        // which calculates it from semaphore state to avoid race conditions
    }

    @Override
    public void recordProcessingSuccess(String poolCode, long durationMs) {
        PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
        metrics.messagesSucceeded.increment();
        metrics.processingTimer.record(Duration.ofMillis(durationMs));
        metrics.totalProcessingTimeMs.addAndGet(durationMs);
        metrics.lastActivityTimestamp.set(System.currentTimeMillis());
    }

    @Override
    public void recordProcessingFailure(String poolCode, long durationMs, String errorType) {
        PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
        metrics.messagesFailed.increment();
        metrics.processingTimer.record(Duration.ofMillis(durationMs));
        metrics.totalProcessingTimeMs.addAndGet(durationMs);
        metrics.lastActivityTimestamp.set(System.currentTimeMillis());

        // Track error type
        Counter errorCounter = Counter.builder("flowcatalyst.pool.errors")
            .tag("pool", poolCode)
            .tag("errorType", errorType)
            .register(meterRegistry);
        errorCounter.increment();
    }

    @Override
    public void recordRateLimitExceeded(String poolCode) {
        PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
        metrics.messagesRateLimited.increment();
    }

    @Override
    public void initializePoolCapacity(String poolCode, int maxConcurrency, int maxQueueCapacity) {
        PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
        metrics.maxConcurrency.set(maxConcurrency);
        metrics.maxQueueCapacity.set(maxQueueCapacity);
    }

    @Override
    public void updatePoolGauges(String poolCode, int activeWorkers, int availablePermits, int queueSize, int messageGroupCount) {
        PoolMetricsHolder metrics = getOrCreateMetrics(poolCode);
        metrics.activeWorkers.set(activeWorkers);
        metrics.availablePermits.set(availablePermits);
        metrics.queueSize.set(queueSize);
        metrics.messageGroupCount.set(messageGroupCount);
    }

    @Override
    public PoolStats getPoolStats(String poolCode) {
        PoolMetricsHolder metrics = poolMetrics.get(poolCode);
        if (metrics == null) {
            return new PoolStats(poolCode, 0, 0, 0, 0, 0.0, 0, 0, 0, 0, 0, 0.0);
        }

        long totalProcessed = (long) (metrics.messagesSucceeded.count() + metrics.messagesFailed.count());
        double successRate = totalProcessed > 0
            ? (metrics.messagesSucceeded.count() / totalProcessed)
            : 0.0;

        double avgProcessingTime = totalProcessed > 0
            ? metrics.totalProcessingTimeMs.get() / (double) totalProcessed
            : 0.0;

        return new PoolStats(
            poolCode,
            totalProcessed,
            (long) metrics.messagesSucceeded.count(),
            (long) metrics.messagesFailed.count(),
            (long) metrics.messagesRateLimited.count(),
            successRate,
            metrics.activeWorkers.get(),
            metrics.availablePermits.get(),
            metrics.maxConcurrency.get(),
            metrics.queueSize.get(),
            metrics.maxQueueCapacity.get(),
            avgProcessingTime
        );
    }

    @Override
    public Map<String, PoolStats> getAllPoolStats() {
        Map<String, PoolStats> allStats = new ConcurrentHashMap<>();
        poolMetrics.forEach((poolCode, metrics) -> {
            allStats.put(poolCode, getPoolStats(poolCode));
        });
        return allStats;
    }

    @Override
    public Long getLastActivityTimestamp(String poolCode) {
        PoolMetricsHolder metrics = poolMetrics.get(poolCode);
        if (metrics == null) {
            return null;
        }
        long timestamp = metrics.lastActivityTimestamp.get();
        return timestamp == 0 ? null : timestamp;
    }

    @Override
    public void removePoolMetrics(String poolCode) {
        PoolMetricsHolder metrics = poolMetrics.remove(poolCode);
        if (metrics != null) {
            LOG.infof("Removing Micrometer metrics for pool: %s", poolCode);

            // Remove all counters from registry
            meterRegistry.remove(metrics.messagesSubmitted.getId());
            meterRegistry.remove(metrics.messagesSucceeded.getId());
            meterRegistry.remove(metrics.messagesFailed.getId());
            meterRegistry.remove(metrics.messagesRateLimited.getId());

            // Remove timer from registry
            meterRegistry.remove(metrics.processingTimer.getId());

            // Remove gauges from registry
            // Gauges are identified by name and tags
            meterRegistry.remove(
                meterRegistry.find("flowcatalyst.pool.workers.active")
                    .tag("pool", poolCode)
                    .meter()
            );
            meterRegistry.remove(
                meterRegistry.find("flowcatalyst.pool.semaphore.available")
                    .tag("pool", poolCode)
                    .meter()
            );
            meterRegistry.remove(
                meterRegistry.find("flowcatalyst.pool.queue.size")
                    .tag("pool", poolCode)
                    .meter()
            );
            meterRegistry.remove(
                meterRegistry.find("flowcatalyst.pool.messagegroups.count")
                    .tag("pool", poolCode)
                    .meter()
            );

            LOG.infof("Successfully removed all metrics for pool: %s", poolCode);
        } else {
            LOG.debugf("No metrics found for pool: %s", poolCode);
        }
    }

    private PoolMetricsHolder getOrCreateMetrics(String poolCode) {
        return poolMetrics.computeIfAbsent(poolCode, code -> {
            LOG.infof("Creating Micrometer metrics for pool: %s", code);

            Counter submitted = Counter.builder("flowcatalyst.pool.messages.submitted")
                .tag("pool", code)
                .description("Total messages submitted to pool")
                .register(meterRegistry);

            Counter succeeded = Counter.builder("flowcatalyst.pool.messages.succeeded")
                .tag("pool", code)
                .description("Total messages processed successfully")
                .register(meterRegistry);

            Counter failed = Counter.builder("flowcatalyst.pool.messages.failed")
                .tag("pool", code)
                .description("Total messages that failed processing")
                .register(meterRegistry);

            Counter rateLimited = Counter.builder("flowcatalyst.pool.messages.ratelimited")
                .tag("pool", code)
                .description("Total messages rejected due to rate limiting")
                .register(meterRegistry);

            Timer processingTimer = Timer.builder("flowcatalyst.pool.processing.duration")
                .tag("pool", code)
                .description("Message processing duration")
                .register(meterRegistry);

            AtomicInteger activeWorkers = new AtomicInteger(0);
            meterRegistry.gauge("flowcatalyst.pool.workers.active", List.of(Tag.of("pool", code)), activeWorkers);

            AtomicInteger availablePermits = new AtomicInteger(0);
            meterRegistry.gauge("flowcatalyst.pool.semaphore.available", List.of(Tag.of("pool", code)), availablePermits);

            AtomicInteger queueSize = new AtomicInteger(0);
            meterRegistry.gauge("flowcatalyst.pool.queue.size", List.of(Tag.of("pool", code)), queueSize);

            AtomicInteger messageGroupCount = new AtomicInteger(0);
            meterRegistry.gauge("flowcatalyst.pool.messagegroups.count", List.of(Tag.of("pool", code)), messageGroupCount);

            return new PoolMetricsHolder(
                submitted,
                succeeded,
                failed,
                rateLimited,
                processingTimer,
                activeWorkers,
                availablePermits,
                queueSize,
                messageGroupCount,
                new AtomicInteger(0), // maxConcurrency - will be set on init
                new AtomicInteger(0), // maxQueueCapacity - will be set on init
                new AtomicLong(0),
                new AtomicLong(0) // lastActivityTimestamp
            );
        });
    }

    /**
     * Internal holder for pool metrics
     */
    private record PoolMetricsHolder(
        Counter messagesSubmitted,
        Counter messagesSucceeded,
        Counter messagesFailed,
        Counter messagesRateLimited,
        Timer processingTimer,
        AtomicInteger activeWorkers,
        AtomicInteger availablePermits,
        AtomicInteger queueSize,
        AtomicInteger messageGroupCount,
        AtomicInteger maxConcurrency,
        AtomicInteger maxQueueCapacity,
        AtomicLong totalProcessingTimeMs,
        AtomicLong lastActivityTimestamp
    ) {}
}
