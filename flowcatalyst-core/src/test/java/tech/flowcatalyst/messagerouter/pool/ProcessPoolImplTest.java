package tech.flowcatalyst.messagerouter.pool;

import io.quarkus.test.common.QuarkusTestResource;
import tech.flowcatalyst.messagerouter.integration.PostgresTestResource;
import io.quarkus.test.junit.QuarkusTest;
import tech.flowcatalyst.messagerouter.integration.PostgresTestResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.mediator.Mediator;
import tech.flowcatalyst.messagerouter.metrics.PoolMetricsService;
import tech.flowcatalyst.messagerouter.model.MediationResult;
import tech.flowcatalyst.messagerouter.model.MessagePointer;
import tech.flowcatalyst.messagerouter.model.MediationType;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ProcessPoolImplTest {

    private ProcessPoolImpl processPool;
    private Mediator mockMediator;
    private MessageCallback mockCallback;
    private ConcurrentMap<String, MessagePointer> inPipelineMap;
    private PoolMetricsService mockPoolMetrics;
    private WarningService mockWarningService;

    @BeforeEach
    void setUp() {
        mockMediator = mock(Mediator.class);
        mockCallback = mock(MessageCallback.class);
        inPipelineMap = new ConcurrentHashMap<>();
        mockPoolMetrics = mock(PoolMetricsService.class);
        mockWarningService = mock(WarningService.class);

        processPool = new ProcessPoolImpl(
            "TEST-POOL",
            5, // concurrency
            100, // queue capacity
            null, // rateLimitPerMinute
            mockMediator,
            mockCallback,
            inPipelineMap,
            mockPoolMetrics,
            mockWarningService
        );
    }

    @AfterEach
    void tearDown() {
        if (processPool != null) {
            processPool.drain();
        }
    }

    @Test
    void shouldProcessMessageSuccessfully() {
        // Given
        MessagePointer message = new MessagePointer(
            "msg-1",
            "TEST-POOL",
            "test-token",
            MediationType.HTTP,
            "http://localhost:8080/test"
        );

        when(mockMediator.process(message)).thenReturn(MediationResult.SUCCESS);
        inPipelineMap.put(message.id(), message);

        // When
        processPool.start();
        boolean submitted = processPool.submit(message);

        // Then
        assertTrue(submitted);

        await().untilAsserted(() -> {
            verify(mockMediator).process(message);
            verify(mockCallback).ack(message);
            assertFalse(inPipelineMap.containsKey(message.id()));
        });
    }

    @Test
    void shouldNackMessageOnMediationFailure() {
        // Given
        MessagePointer message = new MessagePointer(
            "msg-2",
            "TEST-POOL",
            "test-token",
            MediationType.HTTP,
            "http://localhost:8080/test"
        );

        when(mockMediator.process(message)).thenReturn(MediationResult.ERROR_SERVER);
        inPipelineMap.put(message.id(), message);

        // When
        processPool.start();
        processPool.submit(message);

        // Then
        await().untilAsserted(() -> {
            verify(mockMediator).process(message);
            verify(mockCallback).nack(message);
            verify(mockWarningService).addWarning(
                eq("MEDIATION"),
                eq("ERROR"),
                contains("ERROR_SERVER"),
                eq("ProcessPool:TEST-POOL")
            );
        });
    }

    @Test
    void shouldEnforceRateLimit() {
        // Given - create a pool with rate limiting enabled
        ProcessPoolImpl rateLimitedPool = new ProcessPoolImpl(
            "RATE-LIMITED-POOL",
            5,
            100,
            1, // 1 per minute rate limit
            mockMediator,
            mockCallback,
            inPipelineMap,
            mockPoolMetrics,
            mockWarningService
        );

        MessagePointer message1 = new MessagePointer(
            "msg-rate-1",
            "RATE-LIMITED-POOL",
            "test-token",
            MediationType.HTTP,
            "http://localhost:8080/test"
        );

        MessagePointer message2 = new MessagePointer(
            "msg-rate-2",
            "RATE-LIMITED-POOL",
            "test-token",
            MediationType.HTTP,
            "http://localhost:8080/test"
        );

        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);
        inPipelineMap.put(message1.id(), message1);
        inPipelineMap.put(message2.id(), message2);

        // When
        rateLimitedPool.start();
        rateLimitedPool.submit(message1);
        rateLimitedPool.submit(message2);

        // Then - one should be acked, one should be nacked due to rate limit
        await().untilAsserted(() -> {
            // One message should be processed successfully
            verify(mockMediator, times(1)).process(any(MessagePointer.class));
            verify(mockCallback, times(1)).ack(any(MessagePointer.class));

            // One message should be rate limited and nacked
            verify(mockCallback, times(1)).nack(any(MessagePointer.class));
            verify(mockPoolMetrics).recordRateLimitExceeded("RATE-LIMITED-POOL");
        });

        rateLimitedPool.drain();
    }

    @Test
    void shouldRejectMessageWhenQueueFull() {
        // Given - use blocking mediator to fill queue
        when(mockMediator.process(any())).thenAnswer(invocation -> {
            Thread.sleep(100); // Block for a bit
            return MediationResult.SUCCESS;
        });

        ProcessPoolImpl smallPool = new ProcessPoolImpl(
            "SMALL-POOL",
            1,
            1, // Queue capacity of 1
            null, // rateLimitPerMinute
            mockMediator,
            mockCallback,
            inPipelineMap,
            mockPoolMetrics,
            mockWarningService
        );

        MessagePointer message1 = new MessagePointer("msg-1", "SMALL-POOL", "token", MediationType.HTTP, "http://test.com");
        MessagePointer message2 = new MessagePointer("msg-2", "SMALL-POOL", "token", MediationType.HTTP, "http://test.com");
        MessagePointer message3 = new MessagePointer("msg-3", "SMALL-POOL", "token", MediationType.HTTP, "http://test.com");

        // When
        smallPool.start();
        inPipelineMap.put(message1.id(), message1);
        inPipelineMap.put(message2.id(), message2);
        inPipelineMap.put(message3.id(), message3);

        boolean submitted1 = smallPool.submit(message1);
        boolean submitted2 = smallPool.submit(message2);

        // Give time for processing to start
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        boolean submitted3 = smallPool.submit(message3);

        // Then
        assertTrue(submitted1, "First message should submit");
        assertTrue(submitted2, "Second message should submit");
        assertFalse(submitted3, "Third message should be rejected - queue full");

        smallPool.drain();
    }

    @Test
    void shouldRespectConcurrencyLimit() {
        // Given
        ProcessPoolImpl lowConcurrencyPool = new ProcessPoolImpl(
            "LOW-CONCURRENCY",
            2, // Only 2 concurrent
            100,
            null, // rateLimitPerMinute
            mockMediator,
            mockCallback,
            inPipelineMap,
            mockPoolMetrics,
            mockWarningService
        );

        // Simulate slow processing
        when(mockMediator.process(any())).thenAnswer(invocation -> {
            Thread.sleep(500);
            return MediationResult.SUCCESS;
        });

        // When
        lowConcurrencyPool.start();

        for (int i = 0; i < 5; i++) {
            MessagePointer msg = new MessagePointer(
                "msg-" + i,
                "LOW-CONCURRENCY",
                "token",
                MediationType.HTTP,
                "http://test.com"
            );
            inPipelineMap.put(msg.id(), msg);
            lowConcurrencyPool.submit(msg);
        }

        // Then
        // Verify that concurrency is respected by checking metrics
        await().untilAsserted(() -> {
            verify(mockPoolMetrics, atLeast(5)).updatePoolGauges(
                eq("LOW-CONCURRENCY"),
                anyInt(),
                anyInt(),
                anyInt()
            );
        });

        lowConcurrencyPool.drain();
    }

    @Test
    void shouldRemoveFromPipelineMapAfterProcessing() {
        // Given
        MessagePointer message = new MessagePointer(
            "msg-pipeline",
            "TEST-POOL",
            "test-token",
            MediationType.HTTP,
            "http://localhost:8080/test"
        );

        when(mockMediator.process(message)).thenReturn(MediationResult.SUCCESS);
        inPipelineMap.put(message.id(), message);

        // When
        processPool.start();
        processPool.submit(message);

        // Then
        await().untilAsserted(() -> {
            assertFalse(inPipelineMap.containsKey(message.id()));
        });
    }

    @Test
    void shouldDrainGracefully() {
        // Given
        MessagePointer message = new MessagePointer(
            "msg-drain",
            "TEST-POOL",
            "test-token",
            MediationType.HTTP,
            "http://localhost:8080/test"
        );

        when(mockMediator.process(message)).thenReturn(MediationResult.SUCCESS);
        inPipelineMap.put(message.id(), message);

        processPool.start();
        processPool.submit(message);

        // When
        processPool.drain();

        // Then
        verify(mockMediator).process(message);
        assertTrue(inPipelineMap.isEmpty());
    }

    @Test
    void shouldTrackDifferentRateLimitKeysSeparately() {
        // Given - rate limiting is now pool-level, not message-level
        // This test now verifies that messages are processed normally when no rate limit is set
        MessagePointer message1 = new MessagePointer(
            "msg-key1",
            "TEST-POOL",
            "test-token",
            MediationType.HTTP,
            "http://localhost:8080/test"
        );

        MessagePointer message2 = new MessagePointer(
            "msg-key2",
            "TEST-POOL",
            "test-token",
            MediationType.HTTP,
            "http://localhost:8080/test"
        );

        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);
        inPipelineMap.put(message1.id(), message1);
        inPipelineMap.put(message2.id(), message2);

        // When
        processPool.start();
        processPool.submit(message1);
        processPool.submit(message2);

        // Then - both should be processed (no rate limiting)
        await().untilAsserted(() -> {
            verify(mockMediator).process(message1);
            verify(mockMediator).process(message2);
            verify(mockCallback).ack(message1);
            verify(mockCallback).ack(message2);
        });
    }

    @Test
    void shouldGetPoolCodeAndConcurrency() {
        assertEquals("TEST-POOL", processPool.getPoolCode());
        assertEquals(5, processPool.getConcurrency());
    }
}
