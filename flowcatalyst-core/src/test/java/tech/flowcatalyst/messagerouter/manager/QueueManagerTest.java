package tech.flowcatalyst.messagerouter.manager;

import io.quarkus.test.common.QuarkusTestResource;
import tech.flowcatalyst.messagerouter.integration.PostgresTestResource;
import io.quarkus.test.junit.QuarkusTest;
import tech.flowcatalyst.messagerouter.integration.PostgresTestResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.mediator.Mediator;
import tech.flowcatalyst.messagerouter.metrics.PoolMetricsService;
import tech.flowcatalyst.messagerouter.model.MediationResult;
import tech.flowcatalyst.messagerouter.model.MessagePointer;
import tech.flowcatalyst.messagerouter.pool.ProcessPool;
import tech.flowcatalyst.messagerouter.pool.ProcessPoolImpl;
import tech.flowcatalyst.messagerouter.warning.WarningService;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive QueueManager tests covering:
 * - Message routing to correct pools
 * - Duplicate message detection (deduplication)
 * - Pool not found error handling
 * - Queue full handling
 * - ACK/NACK callback delegation
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class QueueManagerTest {

    private QueueManager queueManager;
    private ConcurrentHashMap<String, ProcessPool> processPools;
    private ConcurrentHashMap<String, MessagePointer> inPipelineMap;
    private ConcurrentHashMap<String, MessageCallback> messageCallbacks;

    private Mediator mockMediator;
    private PoolMetricsService mockPoolMetrics;
    private WarningService mockWarningService;

    @BeforeEach
    void setUp() throws Exception {
        queueManager = new QueueManager();

        // Use reflection to access private fields for testing
        processPools = getPrivateField(queueManager, "processPools");
        inPipelineMap = getPrivateField(queueManager, "inPipelineMap");
        messageCallbacks = getPrivateField(queueManager, "messageCallbacks");

        // Create mocks
        mockMediator = mock(Mediator.class);
        mockPoolMetrics = mock(PoolMetricsService.class);
        mockWarningService = mock(WarningService.class);

        // Inject mocked dependencies into QueueManager
        setPrivateField(queueManager, "poolMetrics", mockPoolMetrics);
        setPrivateField(queueManager, "warningService", mockWarningService);

        // Clear any existing state
        processPools.clear();
        inPipelineMap.clear();
        messageCallbacks.clear();
    }

    @AfterEach
    void tearDown() {
        // Clean up pools
        for (ProcessPool pool : processPools.values()) {
            try {
                pool.drain();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        processPools.clear();
        inPipelineMap.clear();
        messageCallbacks.clear();
    }

    @Test
    void shouldRouteMessageToCorrectPool() {
        // Given
        ProcessPool pool = createAndRegisterPool("TEST-POOL", 5, 100);

        MessagePointer message = new MessagePointer(
            "msg-1",
            "TEST-POOL",
            null,
            null,
            "test-token",
            "HTTP",
            "http://localhost:8080/test"
        );

        MessageCallback mockCallback = mock(MessageCallback.class);
        when(mockMediator.process(message)).thenReturn(MediationResult.SUCCESS);

        // When
        boolean routed = queueManager.routeMessage(message, mockCallback);

        // Then
        assertTrue(routed, "Message should be routed successfully");
        assertTrue(inPipelineMap.containsKey(message.id()), "Message should be in pipeline map");
        assertTrue(messageCallbacks.containsKey(message.id()), "Callback should be registered");

        // Wait for processing
        await().untilAsserted(() -> {
            verify(mockMediator).process(message);
        });
    }

    @Test
    void shouldDetectDuplicateMessages() {
        // Given
        ProcessPool pool = createAndRegisterPool("TEST-POOL", 5, 100);

        MessagePointer message = new MessagePointer(
            "msg-duplicate",
            "TEST-POOL",
            null,
            null,
            "test-token",
            "HTTP",
            "http://localhost:8080/test"
        );

        MessageCallback mockCallback1 = mock(MessageCallback.class);
        MessageCallback mockCallback2 = mock(MessageCallback.class);

        when(mockMediator.process(message)).thenReturn(MediationResult.SUCCESS);

        // When
        boolean firstRoute = queueManager.routeMessage(message, mockCallback1);
        boolean secondRoute = queueManager.routeMessage(message, mockCallback2);

        // Then
        assertTrue(firstRoute, "First message should be routed");
        assertFalse(secondRoute, "Duplicate message should be rejected");
        assertEquals(1, inPipelineMap.size(), "Only one message should be in pipeline");
    }

    @Test
    void shouldHandlePoolNotFound() {
        // Given
        MessagePointer message = new MessagePointer(
            "msg-no-pool",
            "NON-EXISTENT-POOL",
            null,
            null,
            "test-token",
            "HTTP",
            "http://localhost:8080/test"
        );

        MessageCallback mockCallback = mock(MessageCallback.class);

        // When
        boolean routed = queueManager.routeMessage(message, mockCallback);

        // Then
        assertFalse(routed, "Message should not be routed when pool doesn't exist");
        assertFalse(inPipelineMap.containsKey(message.id()), "Message should not be in pipeline");
        assertFalse(messageCallbacks.containsKey(message.id()), "Callback should not be registered");

        // Verify warning was added
        verify(mockWarningService).addWarning(
            eq("ROUTING"),
            eq("ERROR"),
            contains("NON-EXISTENT-POOL"),
            eq("QueueManager")
        );
    }

    @Test
    void shouldHandleQueueFull() {
        // Given - create pool with minimal capacity
        ProcessPool smallPool = createAndRegisterPool("SMALL-POOL", 1, 1);

        // Block the mediator to fill the queue
        when(mockMediator.process(any())).thenAnswer(invocation -> {
            Thread.sleep(200);
            return MediationResult.SUCCESS;
        });

        MessagePointer message1 = new MessagePointer("msg-1", "SMALL-POOL", null, null, "token", "HTTP", "http://test.com");
        MessagePointer message2 = new MessagePointer("msg-2", "SMALL-POOL", null, null, "token", "HTTP", "http://test.com");
        MessagePointer message3 = new MessagePointer("msg-3", "SMALL-POOL", null, null, "token", "HTTP", "http://test.com");

        MessageCallback mockCallback = mock(MessageCallback.class);

        // When
        boolean routed1 = queueManager.routeMessage(message1, mockCallback);
        boolean routed2 = queueManager.routeMessage(message2, mockCallback);

        // Give time for processing to start
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        boolean routed3 = queueManager.routeMessage(message3, mockCallback);

        // Then
        assertTrue(routed1, "First message should be routed");
        assertTrue(routed2, "Second message should be routed");
        assertFalse(routed3, "Third message should be rejected - queue full");

        assertFalse(inPipelineMap.containsKey(message3.id()), "Rejected message should not be in pipeline");
        assertFalse(messageCallbacks.containsKey(message3.id()), "Rejected message callback should not be registered");

        // Verify warning was added
        verify(mockWarningService, atLeastOnce()).addWarning(
            eq("QUEUE_FULL"),
            eq("WARN"),
            contains("SMALL-POOL"),
            eq("QueueManager")
        );
    }

    @Test
    void shouldDelegateAckToCallback() {
        // Given
        ProcessPool pool = createAndRegisterPool("TEST-POOL", 5, 100);

        MessagePointer message = new MessagePointer(
            "msg-ack",
            "TEST-POOL",
            null,
            null,
            "test-token",
            "HTTP",
            "http://localhost:8080/test"
        );

        MessageCallback mockCallback = mock(MessageCallback.class);
        when(mockMediator.process(message)).thenReturn(MediationResult.SUCCESS);

        // Route the message
        queueManager.routeMessage(message, mockCallback);

        // Wait for processing to complete
        await().untilAsserted(() -> {
            verify(mockMediator).process(message);
        });

        // When
        queueManager.ack(message);

        // Then
        verify(mockCallback).ack(message);
        assertFalse(messageCallbacks.containsKey(message.id()), "Callback should be removed after ack");
    }

    @Test
    void shouldDelegateNackToCallback() {
        // Given
        ProcessPool pool = createAndRegisterPool("TEST-POOL", 5, 100);

        MessagePointer message = new MessagePointer(
            "msg-nack",
            "TEST-POOL",
            null,
            null,
            "test-token",
            "HTTP",
            "http://localhost:8080/test"
        );

        MessageCallback mockCallback = mock(MessageCallback.class);
        when(mockMediator.process(message)).thenReturn(MediationResult.ERROR_SERVER);

        // Route the message
        queueManager.routeMessage(message, mockCallback);

        // Wait for processing to complete
        await().untilAsserted(() -> {
            verify(mockMediator).process(message);
        });

        // When
        queueManager.nack(message);

        // Then
        verify(mockCallback).nack(message);
        assertFalse(messageCallbacks.containsKey(message.id()), "Callback should be removed after nack");
    }

    @Test
    void shouldHandleAckWithoutCallback() {
        // Given
        MessagePointer message = new MessagePointer(
            "msg-orphan",
            "TEST-POOL",
            null,
            null,
            "test-token",
            "HTTP",
            "http://localhost:8080/test"
        );

        // When - ack without registering callback first
        assertDoesNotThrow(() -> queueManager.ack(message));

        // Then - should not throw exception
    }

    @Test
    void shouldHandleNackWithoutCallback() {
        // Given
        MessagePointer message = new MessagePointer(
            "msg-orphan-nack",
            "TEST-POOL",
            null,
            null,
            "test-token",
            "HTTP",
            "http://localhost:8080/test"
        );

        // When - nack without registering callback first
        assertDoesNotThrow(() -> queueManager.nack(message));

        // Then - should not throw exception
    }

    @Test
    void shouldRouteMultipleMessagesToDifferentPools() {
        // Given
        ProcessPool pool1 = createAndRegisterPool("POOL-1", 5, 100);
        ProcessPool pool2 = createAndRegisterPool("POOL-2", 5, 100);

        MessagePointer message1 = new MessagePointer("msg-1", "POOL-1", null, null, "token", "HTTP", "http://test.com");
        MessagePointer message2 = new MessagePointer("msg-2", "POOL-2", null, null, "token", "HTTP", "http://test.com");

        MessageCallback mockCallback1 = mock(MessageCallback.class);
        MessageCallback mockCallback2 = mock(MessageCallback.class);

        when(mockMediator.process(any())).thenReturn(MediationResult.SUCCESS);

        // When
        boolean routed1 = queueManager.routeMessage(message1, mockCallback1);
        boolean routed2 = queueManager.routeMessage(message2, mockCallback2);

        // Then
        assertTrue(routed1, "Message 1 should be routed to POOL-1");
        assertTrue(routed2, "Message 2 should be routed to POOL-2");
        assertEquals(2, inPipelineMap.size(), "Both messages should be in pipeline");

        await().untilAsserted(() -> {
            verify(mockMediator, times(2)).process(any(MessagePointer.class));
        });
    }

    /**
     * Helper method to create and register a pool for testing
     */
    private ProcessPool createAndRegisterPool(String poolCode, int concurrency, int queueCapacity) {
        ProcessPoolImpl pool = new ProcessPoolImpl(
            poolCode,
            concurrency,
            queueCapacity,
            mockMediator,
            queueManager,
            inPipelineMap,
            mockPoolMetrics,
            mockWarningService
        );

        pool.start();
        processPools.put(poolCode, pool);

        return pool;
    }

    /**
     * Helper method to access private fields via reflection
     */
    @SuppressWarnings("unchecked")
    private <T> T getPrivateField(Object obj, String fieldName) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
    }

    /**
     * Helper method to set private fields via reflection
     */
    private void setPrivateField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }
}
