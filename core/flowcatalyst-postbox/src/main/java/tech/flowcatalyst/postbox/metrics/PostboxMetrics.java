package tech.flowcatalyst.postbox.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.flowcatalyst.postbox.entity.PostboxMessage;

/**
 * Metrics for postbox operations
 * Tracks message processing, failures, retry counts, and poller status
 */
@ApplicationScoped
public class PostboxMetrics {

    @Inject
    MeterRegistry meterRegistry;

    private Counter messagesProcessed;
    private Counter messagesFailed;
    private Counter messagesRetried;
    private Timer processingTime;

    public PostboxMetrics() {
    }

    /**
     * Initialize metrics (called lazily on first use)
     */
    private void initialize() {
        if (messagesProcessed == null) {
            messagesProcessed = Counter.builder("postbox.messages.processed")
                    .description("Total messages successfully processed")
                    .register(meterRegistry);

            messagesFailed = Counter.builder("postbox.messages.failed")
                    .description("Total messages that failed to process")
                    .register(meterRegistry);

            messagesRetried = Counter.builder("postbox.messages.retried")
                    .description("Total retry attempts")
                    .register(meterRegistry);

            processingTime = Timer.builder("postbox.processing.time")
                    .description("Time to process a message")
                    .register(meterRegistry);
        }
    }

    /**
     * Record a successfully processed message
     */
    public void recordMessageProcessed(PostboxMessage message) {
        initialize();
        messagesProcessed.increment();
        meterRegistry.gauge("postbox.message.size.bytes",
                message.payloadSize != null ? message.payloadSize : 0);
    }

    /**
     * Record a failed message
     */
    public void recordMessageFailed(PostboxMessage message) {
        initialize();
        messagesFailed.increment();
    }

    /**
     * Record a message retry
     */
    public void recordMessageRetry(PostboxMessage message) {
        initialize();
        messagesRetried.increment();
    }

    /**
     * Record processing time for a message
     */
    public Timer.Sample startProcessingTimer() {
        initialize();
        return Timer.start(meterRegistry);
    }

    /**
     * Stop processing timer and record
     */
    public void stopProcessingTimer(Timer.Sample sample) {
        initialize();
        sample.stop(processingTime);
    }

    /**
     * Record poller count
     */
    public void recordPollerCount(int count) {
        initialize();
        meterRegistry.gauge("postbox.pollers.active", count);
    }

}
