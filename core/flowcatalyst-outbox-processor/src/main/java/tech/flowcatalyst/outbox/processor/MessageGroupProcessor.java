package tech.flowcatalyst.outbox.processor;

import org.jboss.logging.Logger;
import tech.flowcatalyst.outbox.api.FlowCatalystApiClient;
import tech.flowcatalyst.outbox.config.OutboxProcessorConfig;
import tech.flowcatalyst.outbox.model.OutboxItem;
import tech.flowcatalyst.outbox.model.OutboxItemType;
import tech.flowcatalyst.outbox.repository.OutboxRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Processes items for a single message group in FIFO order.
 * Uses virtual threads for efficient async processing.
 * Only one batch is sent at a time per group to maintain ordering.
 */
public class MessageGroupProcessor {

    private static final Logger LOG = Logger.getLogger(MessageGroupProcessor.class);

    private final OutboxItemType type;
    private final String messageGroup;
    private final BlockingQueue<OutboxItem> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Semaphore globalSemaphore;

    private final OutboxProcessorConfig config;
    private final OutboxRepository repository;
    private final FlowCatalystApiClient apiClient;

    public MessageGroupProcessor(
            OutboxItemType type,
            String messageGroup,
            Semaphore globalSemaphore,
            OutboxProcessorConfig config,
            OutboxRepository repository,
            FlowCatalystApiClient apiClient) {
        this.type = type;
        this.messageGroup = messageGroup;
        this.globalSemaphore = globalSemaphore;
        this.config = config;
        this.repository = repository;
        this.apiClient = apiClient;
    }

    /**
     * Enqueue an item for processing.
     * Starts the processing loop if not already running.
     */
    public void enqueue(OutboxItem item) {
        queue.offer(item);
        tryStartProcessing();
    }

    /**
     * Get the number of items waiting in this processor's queue.
     */
    public int getQueueSize() {
        return queue.size();
    }

    private void tryStartProcessing() {
        if (running.compareAndSet(false, true)) {
            Thread.startVirtualThread(this::processLoop);
        }
    }

    private void processLoop() {
        try {
            while (!queue.isEmpty()) {
                // Acquire global semaphore to limit concurrent group processing
                globalSemaphore.acquire();
                try {
                    processBatch();
                } finally {
                    globalSemaphore.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("MessageGroupProcessor interrupted for group %s:%s", type, messageGroup);
        } finally {
            running.set(false);
            // Check if more items arrived while we were finishing
            if (!queue.isEmpty()) {
                tryStartProcessing();
            }
        }
    }

    private void processBatch() {
        // Drain up to batch size from queue
        List<OutboxItem> batch = new ArrayList<>();
        queue.drainTo(batch, config.apiBatchSize());

        if (batch.isEmpty()) {
            return;
        }

        List<String> ids = batch.stream().map(OutboxItem::id).toList();
        LOG.debugf("Processing batch of %d %s items for group %s", batch.size(), type, messageGroup);

        try {
            // Call FlowCatalyst API
            if (type == OutboxItemType.EVENT) {
                apiClient.createEventsBatch(batch);
            } else {
                apiClient.createDispatchJobsBatch(batch);
            }

            // Mark as completed
            repository.markCompleted(type, ids);
            LOG.debugf("Completed batch of %d %s items for group %s", batch.size(), type, messageGroup);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process batch for group %s:%s", type, messageGroup);
            handleFailure(batch, e.getMessage());
        }
    }

    private void handleFailure(List<OutboxItem> batch, String errorMessage) {
        // Separate items that can be retried from those that have exhausted retries
        List<String> retryable = batch.stream()
            .filter(item -> item.retryCount() < config.maxRetries())
            .map(OutboxItem::id)
            .toList();

        List<String> failed = batch.stream()
            .filter(item -> item.retryCount() >= config.maxRetries())
            .map(OutboxItem::id)
            .toList();

        if (!retryable.isEmpty()) {
            repository.scheduleRetry(type, retryable);
            LOG.infof("Scheduled %d items for retry in group %s:%s", retryable.size(), type, messageGroup);
        }

        if (!failed.isEmpty()) {
            repository.markFailed(type, failed, errorMessage);
            LOG.warnf("Marked %d items as FAILED in group %s:%s (max retries exceeded)", failed.size(), type, messageGroup);
        }
    }
}
