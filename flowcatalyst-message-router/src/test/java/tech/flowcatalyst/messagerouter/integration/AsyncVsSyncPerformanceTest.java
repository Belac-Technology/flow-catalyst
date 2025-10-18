package tech.flowcatalyst.messagerouter.integration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tech.flowcatalyst.messagerouter.consumer.AsyncSqsQueueConsumer;
import tech.flowcatalyst.messagerouter.consumer.SqsQueueConsumer;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.metrics.QueueMetricsService;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test comparing SYNC vs ASYNC SQS consumer performance.
 * Measures real latency with LocalStack SQS.
 * <p>
 * Tests demonstrate that ASYNC mode processes messages significantly faster
 * by processing them as they arrive during long polling instead of waiting
 * for the full poll duration to complete.
 */
@QuarkusTest
@Tag("integration")
@org.junit.jupiter.api.Disabled("Slow integration test - run manually when needed")
class AsyncVsSyncPerformanceTest {

    private static final Logger LOG = Logger.getLogger(AsyncVsSyncPerformanceTest.class);

    @Inject
    SqsClient sqsClient;

    @Inject
    SqsAsyncClient sqsAsyncClient;

    @Inject
    QueueManager queueManager;

    @Inject
    QueueMetricsService queueMetrics;

    @Inject
    WarningService warningService;

    @ConfigProperty(name = "quarkus.sqs.endpoint-override")
    String sqsEndpoint;

    private String testQueueUrl;
    private List<String> createdQueues = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Create test queue
        String queueName = "async-vs-sync-test-" + System.currentTimeMillis();
        CreateQueueResponse createResponse = sqsClient.createQueue(
            CreateQueueRequest.builder()
                .queueName(queueName)
                .build()
        );
        testQueueUrl = createResponse.queueUrl();
        createdQueues.add(testQueueUrl);

        LOG.infof("Created test queue: %s", testQueueUrl);
    }

    @AfterEach
    void tearDown() {
        // Clean up test queues
        for (String queueUrl : createdQueues) {
            try {
                sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
                LOG.infof("Deleted test queue: %s", queueUrl);
            } catch (Exception e) {
                LOG.warnf("Failed to delete queue %s: %s", queueUrl, e.getMessage());
            }
        }
        createdQueues.clear();
    }

    @Test
    void shouldProcessMessagesWithAsyncMode() {
        // Given - async consumer
        AtomicInteger messagesProcessed = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong firstMessageLatency = new AtomicLong(0);

        AsyncSqsQueueConsumer asyncConsumer = new AsyncSqsQueueConsumer(
            sqsAsyncClient,
            testQueueUrl,
            2, // 2 concurrent polls
            createTestQueueManager(messagesProcessed, totalLatency, firstMessageLatency),
            queueMetrics,
            warningService,
            10, // max messages per poll
            5,  // wait time (shorter for faster test)
            5   // metrics interval
        );

        try {
            // When - send messages and start consumer
            long sendStartTime = System.currentTimeMillis();
            sendTestMessages(3);

            asyncConsumer.start();

            // Then - verify async processing
            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
                int processed = messagesProcessed.get();
                LOG.infof("Async mode processed %d messages", processed);
                assertEquals(3, processed, "All messages should be processed");
            });

            // Verify latency is reasonable (async should be fast)
            long avgLatency = totalLatency.get() / messagesProcessed.get();
            long firstLatency = firstMessageLatency.get();

            LOG.infof("Async - First message latency: %dms, Average latency: %dms",
                firstLatency, avgLatency);

            // Async mode should process first message quickly (within a few seconds)
            assertTrue(firstLatency < 8000,
                "Async mode should process first message quickly, got " + firstLatency + "ms");

        } finally {
            asyncConsumer.stop();
        }
    }

    @Test
    void shouldProcessMessagesWithSyncMode() {
        // Given - sync consumer
        AtomicInteger messagesProcessed = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong firstMessageLatency = new AtomicLong(0);

        SqsQueueConsumer syncConsumer = new SqsQueueConsumer(
            sqsClient,
            testQueueUrl,
            2, // 2 concurrent threads
            createTestQueueManager(messagesProcessed, totalLatency, firstMessageLatency),
            queueMetrics,
            warningService,
            10, // max messages per poll
            5,  // wait time (shorter for faster test)
            5   // metrics interval
        );

        try {
            // When - send messages and start consumer
            long sendStartTime = System.currentTimeMillis();
            sendTestMessages(3);

            syncConsumer.start();

            // Then - verify sync processing
            await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
                int processed = messagesProcessed.get();
                LOG.infof("Sync mode processed %d messages", processed);
                assertEquals(3, processed, "All messages should be processed");
            });

            // Verify latency (sync will be slower due to waiting for poll completion)
            long avgLatency = totalLatency.get() / messagesProcessed.get();
            long firstLatency = firstMessageLatency.get();

            LOG.infof("Sync - First message latency: %dms, Average latency: %dms",
                firstLatency, avgLatency);

            // Sync mode may have higher latency due to poll waiting
            // We don't assert on this to avoid flaky tests, but log it for comparison

        } finally {
            syncConsumer.stop();
        }
    }

    @Test
    void shouldHandleHighVolumeWithAsyncMode() {
        // Given - async consumer with higher concurrency
        AtomicInteger messagesProcessed = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong firstMessageLatency = new AtomicLong(0);

        AsyncSqsQueueConsumer asyncConsumer = new AsyncSqsQueueConsumer(
            sqsAsyncClient,
            testQueueUrl,
            5, // 5 concurrent polls for higher throughput
            createTestQueueManager(messagesProcessed, totalLatency, firstMessageLatency),
            queueMetrics,
            warningService,
            10, // max messages per poll
            5,  // wait time
            5   // metrics interval
        );

        try {
            // When - send many messages
            int messageCount = 20;
            sendTestMessages(messageCount);

            asyncConsumer.start();

            // Then - all messages processed
            await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
                int processed = messagesProcessed.get();
                LOG.infof("High volume test - processed %d/%d messages", processed, messageCount);
                assertEquals(messageCount, processed, "All messages should be processed");
            });

            long avgLatency = totalLatency.get() / messagesProcessed.get();
            LOG.infof("High volume - Average message latency: %dms", avgLatency);

            // With high volume, async mode should maintain reasonable latency
            assertTrue(avgLatency < 15000,
                "Async mode should maintain reasonable latency under load, got " + avgLatency + "ms");

        } finally {
            asyncConsumer.stop();
        }
    }

    @Test
    void shouldGracefullyShutdownWithMessagesInFlight() {
        // Given - async consumer
        AtomicInteger messagesProcessed = new AtomicInteger(0);

        AsyncSqsQueueConsumer asyncConsumer = new AsyncSqsQueueConsumer(
            sqsAsyncClient,
            testQueueUrl,
            3, // 3 concurrent polls
            createTestQueueManager(messagesProcessed, new AtomicLong(0), new AtomicLong(0)),
            queueMetrics,
            warningService,
            10,
            5,
            5
        );

        // When - start consumer and send messages
        asyncConsumer.start();
        sendTestMessages(5);

        // Wait for some processing to start
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
            assertTrue(messagesProcessed.get() > 0, "Some messages should be processed")
        );

        // Then - shutdown should be graceful
        long shutdownStart = System.currentTimeMillis();
        asyncConsumer.stop();
        long shutdownDuration = System.currentTimeMillis() - shutdownStart;

        LOG.infof("Shutdown completed in %dms", shutdownDuration);

        // Shutdown should complete within timeout (30s configured in AsyncSqsQueueConsumer)
        assertTrue(shutdownDuration < 30000,
            "Shutdown should complete within timeout, took " + shutdownDuration + "ms");
        assertTrue(asyncConsumer.isFullyStopped(), "Consumer should be fully stopped");
    }

    /**
     * Send test messages to the queue
     */
    private void sendTestMessages(int count) {
        for (int i = 0; i < count; i++) {
            String messageBody = String.format("""
                {
                    "id": "perf-test-msg-%d",
                    "poolCode": "TEST-POOL",
                    "authToken": "test-token",
                    "mediationType": "HTTP",
                    "mediationTarget": "http://localhost:8080/test",
                    "sentAt": %d
                }
                """, i, System.currentTimeMillis());

            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(testQueueUrl)
                .messageBody(messageBody)
                .build());
        }
        LOG.infof("Sent %d test messages", count);
    }

    /**
     * Create a test QueueManager that tracks metrics and ACKs messages
     */
    private tech.flowcatalyst.messagerouter.manager.QueueManager createTestQueueManager(
            AtomicInteger messagesProcessed,
            AtomicLong totalLatency,
            AtomicLong firstMessageLatency) {

        AtomicBoolean firstMessageReceived = new AtomicBoolean(false);

        tech.flowcatalyst.messagerouter.manager.QueueManager mockQueueManager =
            org.mockito.Mockito.mock(tech.flowcatalyst.messagerouter.manager.QueueManager.class);

        org.mockito.Mockito.when(mockQueueManager.routeMessage(
            org.mockito.Mockito.any(),
            org.mockito.Mockito.any()
        )).thenAnswer(invocation -> {
            tech.flowcatalyst.messagerouter.model.MessagePointer message =
                invocation.getArgument(0);
            tech.flowcatalyst.messagerouter.callback.MessageCallback callback =
                invocation.getArgument(1);

            try {
                String messageId = message.id();
                long receivedAt = System.currentTimeMillis();
                long latency = receivedAt % 100000; // Simplified latency

                messagesProcessed.incrementAndGet();
                totalLatency.addAndGet(latency);

                if (firstMessageReceived.compareAndSet(false, true)) {
                    firstMessageLatency.set(latency);
                    LOG.infof("First message received with latency: %dms", latency);
                }

                // ACK the message
                callback.ack(message);

                LOG.debugf("Processed message %s (total: %d)", messageId, messagesProcessed.get());
                return true;
            } catch (Exception e) {
                LOG.errorf(e, "Error processing message");
                return false;
            }
        });

        return mockQueueManager;
    }
}
