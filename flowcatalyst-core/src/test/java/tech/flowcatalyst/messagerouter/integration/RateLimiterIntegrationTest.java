package tech.flowcatalyst.messagerouter.integration;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.mediator.Mediator;
import tech.flowcatalyst.messagerouter.metrics.PoolMetricsService;
import tech.flowcatalyst.messagerouter.model.MediationResult;
import tech.flowcatalyst.messagerouter.model.MessagePointer;
import tech.flowcatalyst.messagerouter.pool.ProcessPoolImpl;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@Tag("integration")
class RateLimiterIntegrationTest {

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
            "RATE-LIMIT-POOL",
            10,
            100,
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
    void shouldAllowMessagesWithinRateLimit() {
        // Given
        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);

        processPool.start();

        // Create 5 messages with rate limit of 60 per minute (should all pass)
        for (int i = 0; i < 5; i++) {
            MessagePointer message = new MessagePointer(
                "msg-" + i,
                "RATE-LIMIT-POOL",
                60, // 60 per minute
                "test-key",
                "token",
                "HTTP",
                "http://localhost:8080/test"
            );
            inPipelineMap.put(message.id(), message);
            processPool.submit(message);
        }

        // Then
        await().untilAsserted(() -> {
            verify(mockMediator, times(5)).process(any());
            verify(mockCallback, times(5)).ack(any());
        });
    }

    @Test
    void shouldEnforceRateLimitAndNackExcessMessages() {
        // Given
        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);
        AtomicInteger ackedCount = new AtomicInteger(0);
        AtomicInteger nackedCount = new AtomicInteger(0);

        doAnswer(invocation -> {
            ackedCount.incrementAndGet();
            return null;
        }).when(mockCallback).ack(any());

        doAnswer(invocation -> {
            nackedCount.incrementAndGet();
            return null;
        }).when(mockCallback).nack(any());

        processPool.start();

        // Create 10 messages with rate limit of 5 per minute
        // First 5 should pass, next 5 should be rate limited
        for (int i = 0; i < 10; i++) {
            MessagePointer message = new MessagePointer(
                "msg-rate-" + i,
                "RATE-LIMIT-POOL",
                5, // Only 5 per minute
                "test-key",
                "token",
                "HTTP",
                "http://localhost:8080/test"
            );
            inPipelineMap.put(message.id(), message);
            processPool.submit(message);
        }

        // Then
        await().untilAsserted(() -> {
            assertTrue(ackedCount.get() >= 5, "Should ack at least 5 messages");
            assertTrue(nackedCount.get() >= 5, "Should nack at least 5 messages due to rate limit");
            verify(mockPoolMetrics, atLeast(5)).recordRateLimitExceeded("RATE-LIMIT-POOL");
        });
    }

    @Test
    void shouldTrackDifferentRateLimitKeysIndependently() {
        // Given
        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);

        processPool.start();

        // Send 3 messages for key-1 with limit of 2
        for (int i = 0; i < 3; i++) {
            MessagePointer message = new MessagePointer(
                "msg-key1-" + i,
                "RATE-LIMIT-POOL",
                2,
                "key-1",
                "token",
                "HTTP",
                "http://localhost:8080/test"
            );
            inPipelineMap.put(message.id(), message);
            processPool.submit(message);
        }

        // Send 3 messages for key-2 with limit of 2
        for (int i = 0; i < 3; i++) {
            MessagePointer message = new MessagePointer(
                "msg-key2-" + i,
                "RATE-LIMIT-POOL",
                2,
                "key-2",
                "token",
                "HTTP",
                "http://localhost:8080/test"
            );
            inPipelineMap.put(message.id(), message);
            processPool.submit(message);
        }

        // Then
        // Each key should have 2 acked and 1 nacked
        await().untilAsserted(() -> {
            verify(mockCallback, times(4)).ack(any()); // 2 from each key
            verify(mockCallback, times(2)).nack(any()); // 1 from each key
        });
    }

    @Test
    void shouldHandleHighRateLimit() {
        // Given
        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);

        processPool.start();

        // Send 50 messages with high rate limit of 600 per minute
        for (int i = 0; i < 50; i++) {
            MessagePointer message = new MessagePointer(
                "msg-high-" + i,
                "RATE-LIMIT-POOL",
                600, // 600 per minute = high limit
                "high-rate-key",
                "token",
                "HTTP",
                "http://localhost:8080/test"
            );
            inPipelineMap.put(message.id(), message);
            processPool.submit(message);
        }

        // Then - all messages should be processed
        await().untilAsserted(() -> {
            verify(mockMediator, times(50)).process(any());
            verify(mockCallback, times(50)).ack(any());
        });
    }

    @Test
    void shouldProcessMessagesWithoutRateLimitImmediately() {
        // Given
        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);

        processPool.start();

        // Send messages without rate limit
        for (int i = 0; i < 10; i++) {
            MessagePointer message = new MessagePointer(
                "msg-no-limit-" + i,
                "RATE-LIMIT-POOL",
                null, // No rate limit
                null, // No rate limit key
                "token",
                "HTTP",
                "http://localhost:8080/test"
            );
            inPipelineMap.put(message.id(), message);
            processPool.submit(message);
        }

        // Then - all should be processed
        await().untilAsserted(() -> {
            verify(mockMediator, times(10)).process(any());
            verify(mockCallback, times(10)).ack(any());
        });
    }

    @Test
    void shouldHandleMissingRateLimitKeyGracefully() {
        // Given
        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);

        processPool.start();

        // Message with rate limit but no key (should be ignored)
        MessagePointer message = new MessagePointer(
            "msg-no-key",
            "RATE-LIMIT-POOL",
            60, // Has limit
            null, // But no key
            "token",
            "HTTP",
            "http://localhost:8080/test"
        );

        inPipelineMap.put(message.id(), message);
        processPool.submit(message);

        // Then - should process normally (rate limit ignored)
        await().untilAsserted(() -> {
            verify(mockMediator).process(message);
            verify(mockCallback).ack(message);
        });
    }
}
