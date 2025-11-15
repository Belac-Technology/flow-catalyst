package tech.flowcatalyst.messagerouter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class MicrometerQueueMetricsService implements QueueMetricsService {

    @Inject
    MeterRegistry meterRegistry;

    private final Map<String, QueueMetricsHolder> queueMetrics = new ConcurrentHashMap<>();

    @Override
    public void recordMessageReceived(String queueIdentifier) {
        getOrCreateMetrics(queueIdentifier).messagesReceived.increment();
    }

    @Override
    public void recordMessageProcessed(String queueIdentifier, boolean success) {
        QueueMetricsHolder metrics = getOrCreateMetrics(queueIdentifier);
        if (success) {
            metrics.messagesConsumed.increment();
        } else {
            metrics.messagesFailed.increment();
        }
        metrics.lastProcessedTime = System.currentTimeMillis();
    }

    @Override
    public void recordQueueDepth(String queueIdentifier, long depth) {
        getOrCreateMetrics(queueIdentifier).currentDepth.set(depth);
    }

    @Override
    public void recordQueueMetrics(String queueIdentifier, long pendingMessages, long messagesNotVisible) {
        QueueMetricsHolder metrics = getOrCreateMetrics(queueIdentifier);
        metrics.pendingMessages.set(pendingMessages);
        metrics.messagesNotVisible.set(messagesNotVisible);
    }

    @Override
    public QueueStats getQueueStats(String queueIdentifier) {
        QueueMetricsHolder metrics = queueMetrics.get(queueIdentifier);
        if (metrics == null) {
            return QueueStats.empty(queueIdentifier);
        }

        return buildStats(queueIdentifier, metrics);
    }

    @Override
    public Map<String, QueueStats> getAllQueueStats() {
        Map<String, QueueStats> allStats = new ConcurrentHashMap<>();
        queueMetrics.forEach((queueId, metrics) ->
            allStats.put(queueId, buildStats(queueId, metrics))
        );
        return allStats;
    }

    private QueueMetricsHolder getOrCreateMetrics(String queueIdentifier) {
        return queueMetrics.computeIfAbsent(queueIdentifier, queueId -> {
            Counter received = Counter.builder("flowcatalyst.queue.messages.received")
                .tag("queue", queueId)
                .description("Total messages received from queue")
                .register(meterRegistry);

            Counter consumed = Counter.builder("flowcatalyst.queue.messages.consumed")
                .tag("queue", queueId)
                .description("Total messages successfully consumed")
                .register(meterRegistry);

            Counter failed = Counter.builder("flowcatalyst.queue.messages.failed")
                .tag("queue", queueId)
                .description("Total messages failed")
                .register(meterRegistry);

            AtomicLong depth = meterRegistry.gauge(
                "flowcatalyst.queue.depth",
                io.micrometer.core.instrument.Tags.of("queue", queueId),
                new AtomicLong(0)
            );

            AtomicLong pending = meterRegistry.gauge(
                "flowcatalyst.queue.pending",
                io.micrometer.core.instrument.Tags.of("queue", queueId),
                new AtomicLong(0)
            );

            AtomicLong notVisible = meterRegistry.gauge(
                "flowcatalyst.queue.not_visible",
                io.micrometer.core.instrument.Tags.of("queue", queueId),
                new AtomicLong(0)
            );

            return new QueueMetricsHolder(received, consumed, failed, depth, pending, notVisible);
        });
    }

    private QueueStats buildStats(String queueIdentifier, QueueMetricsHolder metrics) {
        long totalMessages = (long) metrics.messagesReceived.count();
        long totalConsumed = (long) metrics.messagesConsumed.count();
        long totalFailed = (long) metrics.messagesFailed.count();
        long currentSize = metrics.currentDepth.get();

        double successRate = totalMessages > 0
            ? (double) totalConsumed / totalMessages
            : 1.0;  // Empty queues show 100% (no failures yet)

        // Calculate throughput: messages per second over last minute
        long now = System.currentTimeMillis();
        long elapsedSeconds = (now - metrics.startTime) / 1000;
        double throughput = elapsedSeconds > 0
            ? (double) totalConsumed / elapsedSeconds
            : 0.0;

        return new QueueStats(
            queueIdentifier,
            totalMessages,
            totalConsumed,
            totalFailed,
            successRate,
            currentSize,
            throughput,
            metrics.pendingMessages.get(),
            metrics.messagesNotVisible.get()
        );
    }

    private static class QueueMetricsHolder {
        final Counter messagesReceived;
        final Counter messagesConsumed;
        final Counter messagesFailed;
        final AtomicLong currentDepth;
        final AtomicLong pendingMessages;
        final AtomicLong messagesNotVisible;
        final long startTime;
        volatile long lastProcessedTime;

        QueueMetricsHolder(Counter received, Counter consumed, Counter failed, AtomicLong depth,
                          AtomicLong pending, AtomicLong notVisible) {
            this.messagesReceived = received;
            this.messagesConsumed = consumed;
            this.messagesFailed = failed;
            this.currentDepth = depth;
            this.pendingMessages = pending;
            this.messagesNotVisible = notVisible;
            this.startTime = System.currentTimeMillis();
            this.lastProcessedTime = System.currentTimeMillis();
        }
    }
}
