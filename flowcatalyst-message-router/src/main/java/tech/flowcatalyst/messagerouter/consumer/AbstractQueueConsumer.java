package tech.flowcatalyst.messagerouter.consumer;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.metrics.QueueMetricsService;
import tech.flowcatalyst.messagerouter.model.MessagePointer;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractQueueConsumer implements QueueConsumer {

    private static final Logger LOG = Logger.getLogger(AbstractQueueConsumer.class);

    protected final QueueManager queueManager;
    protected final QueueMetricsService queueMetrics;
    protected final WarningService warningService;
    protected final ObjectMapper objectMapper;
    protected final ExecutorService executorService;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final int connections;

    protected AbstractQueueConsumer(QueueManager queueManager, QueueMetricsService queueMetrics, WarningService warningService, int connections) {
        this.queueManager = queueManager;
        this.queueMetrics = queueMetrics;
        this.warningService = warningService;
        this.connections = connections;
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            LOG.infof("Starting consumer for queue [%s] with %d connections", getQueueIdentifier(), connections);
            for (int i = 0; i < connections; i++) {
                executorService.submit(this::consumeMessages);
            }
            // Start queue metrics polling if supported
            executorService.submit(this::pollQueueMetrics);
        }
    }

    @Override
    public void stop() {
        LOG.infof("Stopping consumer for queue [%s] - allowing current polls to complete", getQueueIdentifier());
        running.set(false);

        // Graceful shutdown - let threads finish their current work
        executorService.shutdown();

        try {
            // Wait for consumers to finish current poll and process remaining messages
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                LOG.warnf("Consumer threads for queue [%s] did not terminate within 30 seconds, forcing shutdown", getQueueIdentifier());
                executorService.shutdownNow();
                // Wait a bit more for forced shutdown
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.errorf("Consumer threads for queue [%s] did not terminate after forced shutdown", getQueueIdentifier());
                }
            } else {
                LOG.infof("Consumer for queue [%s] stopped cleanly", getQueueIdentifier());
            }
        } catch (InterruptedException e) {
            LOG.warnf("Interrupted while stopping consumer for queue [%s], forcing shutdown", getQueueIdentifier());
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isFullyStopped() {
        return executorService.isTerminated();
    }

    /**
     * Common message processing flow with MDC context
     */
    protected void processMessage(String rawMessage, MessageCallback callback) {
        String queueId = getQueueIdentifier();

        try {
            // Record message received
            queueMetrics.recordMessageReceived(queueId);

            // Parse message body to MessagePointer
            MessagePointer messagePointer = objectMapper.readValue(rawMessage, MessagePointer.class);

            // Set MDC context for structured logging
            MDC.put("messageId", messagePointer.id());
            MDC.put("queueName", queueId);
            MDC.put("poolCode", messagePointer.poolCode());
            MDC.put("mediationType", messagePointer.mediationType().toString());
            MDC.put("mediationTarget", messagePointer.mediationTarget());

            // Route message to queue manager with callback
            boolean routed = queueManager.routeMessage(messagePointer, callback);

            // If not routed (already in pipeline or pool full), just discard
            if (!routed) {
                LOG.debug("Message not routed (duplicate or pool full), discarding");
                queueMetrics.recordMessageProcessed(queueId, false);
                onMessageNotRouted(messagePointer);
            } else {
                LOG.debug("Message routed to pool");
                queueMetrics.recordMessageProcessed(queueId, true);
            }

            MDC.clear();

        } catch (JsonParseException e) {
            // Malformed message - poison pill that will never parse correctly
            LOG.warnf(e, "Malformed message from queue [%s], acknowledging to remove from queue: %s",
                queueId, rawMessage.substring(0, Math.min(100, rawMessage.length())));

            warningService.addWarning(
                "MALFORMED_MESSAGE",
                "WARN",
                String.format("Malformed message from queue [%s]: %s",
                    queueId, e.getMessage()),
                "AbstractQueueConsumer"
            );

            queueMetrics.recordMessageProcessed(queueId, false);

            // ACK the message to remove it from the queue and prevent infinite retries
            callback.ack(new MessagePointer(
                "unknown",
                "unknown",
                null,
                tech.flowcatalyst.messagerouter.model.MediationType.HTTP,
                "unknown"
            ));

            MDC.clear();
        } catch (Exception e) {
            LOG.errorf(e, "Error processing message from queue [%s]", queueId);
            queueMetrics.recordMessageProcessed(queueId, false);
            MDC.clear();
            onMessageError(rawMessage, e);
        }
    }

    /**
     * Queue-specific implementation to consume messages
     */
    protected abstract void consumeMessages();

    /**
     * Poll queue-specific metrics (pending messages, in-flight messages)
     * Override to implement queue-specific metrics polling
     */
    protected void pollQueueMetrics() {
        // Default: do nothing
        // Subclasses can override to implement queue-specific metrics polling
    }

    /**
     * Called when a message is not routed (duplicate or pool full)
     * Override to implement queue-specific behavior
     */
    protected void onMessageNotRouted(MessagePointer message) {
        // Default: do nothing, let queue visibility timeout handle it
    }

    /**
     * Called when message parsing or processing fails
     * Override to implement queue-specific error handling (e.g., move to DLQ)
     */
    protected void onMessageError(String rawMessage, Exception error) {
        // Default: do nothing, let queue visibility timeout handle it
    }
}
