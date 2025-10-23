package tech.flowcatalyst.messagerouter.consumer;

import org.jboss.logging.Logger;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.model.MessagePointer;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Async SQS consumer using CompletableFuture for non-blocking message processing.
 * <p>
 * Unlike the synchronous SqsQueueConsumer that blocks during long polling,
 * this implementation processes messages immediately as they arrive during the poll.
 * This reduces latency by ~20x for time-sensitive workloads.
 * <p>
 * Architecture:
 * - Multiple concurrent polling tasks (CompletableFuture chains)
 * - Each poll starts a new poll upon completion (continuous polling)
 * - Messages are processed immediately when received (no batch waiting)
 * - Graceful shutdown awaits all active polls to complete
 */
public class AsyncSqsQueueConsumer extends AbstractQueueConsumer {

    private static final Logger LOG = Logger.getLogger(AsyncSqsQueueConsumer.class);

    private final SqsAsyncClient sqsAsyncClient;
    private final String queueUrl;
    private final int maxMessagesPerPoll;
    private final int waitTimeSeconds;
    private final int metricsPollIntervalMs;
    private final int pollingConcurrency;

    // Track active polls for graceful shutdown
    private final CopyOnWriteArrayList<CompletableFuture<Void>> activePolls = new CopyOnWriteArrayList<>();
    private final AtomicInteger activePollCount = new AtomicInteger(0);

    // Scheduler for error backoff
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);


    public AsyncSqsQueueConsumer(
            SqsAsyncClient sqsAsyncClient,
            String queueUrl,
            int connections,
            QueueManager queueManager,
            tech.flowcatalyst.messagerouter.metrics.QueueMetricsService queueMetrics,
            WarningService warningService,
            int maxMessagesPerPoll,
            int waitTimeSeconds,
            int metricsPollIntervalSeconds) {
        super(queueManager, queueMetrics, warningService, connections);
        this.sqsAsyncClient = sqsAsyncClient;
        this.queueUrl = queueUrl;
        this.maxMessagesPerPoll = maxMessagesPerPoll;
        this.waitTimeSeconds = waitTimeSeconds;
        this.metricsPollIntervalMs = metricsPollIntervalSeconds * 1000;
        // Polling concurrency = number of concurrent async polls in flight
        // Set to connections to match sync behavior (N concurrent polls)
        this.pollingConcurrency = connections;
    }

    @Override
    public String getQueueIdentifier() {
        return queueUrl;
    }

    /**
     * Override to use async polling model.
     * Submits N concurrent polling tasks and keeps main thread alive.
     */
    @Override
    protected void startConsumption() {
        LOG.infof("Starting async SQS consumer with %d concurrent polling tasks", pollingConcurrency);

        // Start N concurrent async polling loops
        for (int i = 0; i < pollingConcurrency; i++) {
            submitAsyncPoll();
        }

        // Submit a monitoring task to keep the main consumption thread alive
        executorService.submit(this::monitorAsyncPolls);
    }

    /**
     * Monitoring loop that keeps the consumption thread alive and logs status.
     */
    private void monitorAsyncPolls() {
        while (running.get()) {
            try {
                Thread.sleep(5000); // Check every 5 seconds
                LOG.debugf("Async consumer active polls: %d/%d", activePollCount.get(), pollingConcurrency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        LOG.info("Async consumer monitoring loop exited");
    }

    /**
     * Submit an async poll. This creates a CompletableFuture chain that:
     * 1. Polls SQS asynchronously
     * 2. Processes messages immediately when they arrive
     * 3. Chains the next poll (continuous polling)
     * 4. Handles errors with backoff
     */
    private void submitAsyncPoll() {
        if (!running.get()) {
            return;
        }

        // Update heartbeat at poll start
        updateHeartbeat();

        // Configure per-request timeout (25s = 20s long poll + 5s buffer)
        AwsRequestOverrideConfiguration overrideConfig = AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(25))
            .build();

        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(maxMessagesPerPoll)
            .waitTimeSeconds(waitTimeSeconds)
            .overrideConfiguration(overrideConfig)
            .build();

        activePollCount.incrementAndGet();

        CompletableFuture<Void> pollFuture = sqsAsyncClient.receiveMessage(receiveRequest)
            .thenAccept(response -> {
                // Process messages immediately as they arrive (during the poll!)
                List<Message> messages = response.messages();

                if (!messages.isEmpty()) {
                    LOG.debugf("Async poll received %d messages, processing with batch policies", messages.size());
                }

                // Convert SQS messages to RawMessage objects for batch processing
                List<RawMessage> rawMessages = messages.stream()
                    .map(msg -> new RawMessage(
                        msg.body(),
                        msg.attributes().getOrDefault("MessageGroupId", null),
                        new SqsMessageCallback(msg.receiptHandle())
                    ))
                    .collect(Collectors.toList());

                // Process entire batch with batch-level policies
                processMessageBatch(rawMessages);
            })
            .whenComplete((result, ex) -> {
                activePollCount.decrementAndGet();

                if (ex != null && running.get()) {
                    // Error occurred - schedule retry with backoff
                    LOG.errorf(ex, "Error in async SQS poll, will retry after backoff");
                    scheduler.schedule(() -> {
                        if (running.get()) {
                            submitAsyncPoll();
                        }
                    }, 1, TimeUnit.SECONDS);
                } else if (ex == null && running.get()) {
                    // Success - chain next poll asynchronously to avoid stack overflow
                    CompletableFuture.runAsync(this::submitAsyncPoll, scheduler);
                } else {
                    LOG.debug("Consumer stopped or error during shutdown, not chaining next poll");
                }
            });

        activePolls.add(pollFuture);

        // Clean up completed futures periodically to prevent memory leak
        pollFuture.whenComplete((result, ex) -> activePolls.remove(pollFuture));
    }

    /**
     * This method is called by AbstractQueueConsumer.start() but not used in async mode.
     * Async mode uses submitAsyncPoll() instead.
     */
    @Override
    protected void consumeMessages() {
        LOG.warn("consumeMessages() called on async consumer - this should not happen");
    }

    /**
     * Override stop to await active polls before shutting down.
     */
    @Override
    public void stop() {
        LOG.infof("Stopping async consumer - waiting for %d active polls to complete", activePollCount.get());
        running.set(false);

        // Wait for all active polls to complete (with timeout)
        try {
            CompletableFuture<Void> allPolls = CompletableFuture.allOf(
                activePolls.toArray(new CompletableFuture[0])
            );

            allPolls.get(30, TimeUnit.SECONDS);
            LOG.info("All async polls completed successfully");
        } catch (TimeoutException e) {
            LOG.warnf("Timeout waiting for async polls to complete after 30s, forcing shutdown");
        } catch (Exception e) {
            LOG.warnf(e, "Error waiting for async polls to complete");
        }

        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Shutdown executor
        executorService.shutdown();
        LOG.info("Async SQS consumer stopped");
    }

    /**
     * Periodically poll SQS for queue metrics (pending messages, in-flight messages)
     */
    @Override
    protected void pollQueueMetrics() {
        while (running.get()) {
            try {
                // Configure per-request timeout for metrics polling (10s is plenty)
                AwsRequestOverrideConfiguration overrideConfig = AwsRequestOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(10))
                    .build();

                GetQueueAttributesRequest request = GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                    )
                    .overrideConfiguration(overrideConfig)
                    .build();

                GetQueueAttributesResponse response = sqsAsyncClient.getQueueAttributes(request).join();

                if (response != null && response.attributes() != null) {
                    long pendingMessages = Long.parseLong(
                        response.attributes().getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0")
                    );
                    long messagesNotVisible = Long.parseLong(
                        response.attributes().getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0")
                    );

                    queueMetrics.recordQueueMetrics(queueUrl, pendingMessages, messagesNotVisible);
                }

                Thread.sleep(metricsPollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    LOG.error("Error polling SQS queue metrics", e);
                    try {
                        Thread.sleep(metricsPollIntervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        LOG.debugf("Async SQS queue metrics polling for queue [%s] exited cleanly", queueUrl);
    }

    /**
     * Inner class for SQS-specific message callback with visibility control
     */
    private class SqsMessageCallback implements MessageCallback, tech.flowcatalyst.messagerouter.callback.MessageVisibilityControl {
        private final String receiptHandle;

        SqsMessageCallback(String receiptHandle) {
            this.receiptHandle = receiptHandle;
        }

        @Override
        public void ack(MessagePointer message) {
            try {
                DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build();

                // Use async delete but don't wait for result (fire and forget)
                sqsAsyncClient.deleteMessage(deleteRequest)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            if (ex.getCause() instanceof ReceiptHandleIsInvalidException) {
                                LOG.debugf("Receipt handle invalid for message [%s] - may already be deleted", message.id());
                            } else {
                                LOG.errorf(ex, "Error deleting message from SQS: %s", message.id());
                            }
                        } else {
                            LOG.debugf("Deleted message [%s] from SQS", message.id());
                        }
                    });
            } catch (Exception e) {
                LOG.errorf(e, "Unexpected error initiating delete for message: %s", message.id());
            }
        }

        @Override
        public void nack(MessagePointer message) {
            // For SQS, this is a no-op - we rely on visibility timeout
            LOG.debugf("Nacked message [%s] - will become visible after timeout", message.id());
        }

        @Override
        public void setFastFailVisibility(MessagePointer message) {
            try {
                ChangeMessageVisibilityRequest request = ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .visibilityTimeout(1) // 1 second for fast retry
                    .build();

                sqsAsyncClient.changeMessageVisibility(request)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            if (ex.getCause() instanceof ReceiptHandleIsInvalidException) {
                                LOG.debugf("Receipt handle invalid for message [%s]", message.id());
                            } else {
                                LOG.warnf(ex, "Failed to set fast-fail visibility for message [%s]", message.id());
                            }
                        } else {
                            LOG.debugf("Set fast-fail visibility (1s) for message [%s]", message.id());
                        }
                    });
            } catch (Exception e) {
                LOG.warnf(e, "Error initiating fast-fail visibility for message [%s]", message.id());
            }
        }

        @Override
        public void resetVisibilityToDefault(MessagePointer message) {
            try {
                ChangeMessageVisibilityRequest request = ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .visibilityTimeout(30) // Standard 30-second visibility
                    .build();

                sqsAsyncClient.changeMessageVisibility(request)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            if (ex.getCause() instanceof ReceiptHandleIsInvalidException) {
                                LOG.debugf("Receipt handle invalid for message [%s]", message.id());
                            } else {
                                LOG.warnf(ex, "Failed to reset visibility for message [%s]", message.id());
                            }
                        } else {
                            LOG.debugf("Reset visibility to 30s for message [%s]", message.id());
                        }
                    });
            } catch (Exception e) {
                LOG.warnf(e, "Error initiating visibility reset for message [%s]", message.id());
            }
        }
    }
}
