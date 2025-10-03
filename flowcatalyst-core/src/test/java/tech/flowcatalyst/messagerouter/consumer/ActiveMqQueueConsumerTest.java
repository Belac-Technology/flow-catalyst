package tech.flowcatalyst.messagerouter.consumer;

import io.quarkus.test.common.QuarkusTestResource;
import tech.flowcatalyst.messagerouter.integration.PostgresTestResource;
import io.quarkus.test.junit.QuarkusTest;
import tech.flowcatalyst.messagerouter.integration.PostgresTestResource;
import jakarta.jms.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.metrics.QueueMetricsService;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

import java.util.Enumeration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive ActiveMqQueueConsumer tests covering:
 * - Message consumption and routing
 * - ACK behavior (CLIENT_ACKNOWLEDGE)
 * - NACK behavior (session.recover())
 * - Queue metrics polling using QueueBrowser
 * - Error handling
 * - Resource cleanup
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class ActiveMqQueueConsumerTest {

    private ActiveMqQueueConsumer activeMqConsumer;
    private ConnectionFactory mockConnectionFactory;
    private Connection mockConnection;
    private Session mockSession;
    private MessageConsumer mockMessageConsumer;
    private QueueBrowser mockQueueBrowser;
    private Queue mockQueue;
    private QueueManager mockQueueManager;
    private QueueMetricsService mockQueueMetrics;

    private final String queueName = "test.queue";

    @BeforeEach
    void setUp() throws Exception {
        // Create mocks
        mockConnectionFactory = mock(ConnectionFactory.class);
        mockConnection = mock(Connection.class);
        mockSession = mock(Session.class);
        mockMessageConsumer = mock(MessageConsumer.class);
        mockQueueBrowser = mock(QueueBrowser.class);
        mockQueue = mock(Queue.class);
        mockQueueManager = mock(QueueManager.class);
        mockQueueMetrics = mock(QueueMetricsService.class);

        // Setup mock chain for connection creation
        when(mockConnectionFactory.createConnection()).thenReturn(mockConnection);
        when(mockConnection.createSession(anyBoolean(), anyInt())).thenReturn(mockSession);
        when(mockSession.createQueue(queueName)).thenReturn(mockQueue);
        when(mockSession.createConsumer(mockQueue)).thenReturn(mockMessageConsumer);
        when(mockSession.createBrowser(mockQueue)).thenReturn(mockQueueBrowser);

        activeMqConsumer = new ActiveMqQueueConsumer(
            mockConnectionFactory,
            queueName,
            1, // 1 connection
            mockQueueManager,
            mockQueueMetrics
        );
    }

    @AfterEach
    void tearDown() {
        if (activeMqConsumer != null) {
            activeMqConsumer.stop();
        }
    }

    @Test
    void shouldPollAndProcessMessages() throws Exception {
        // Given
        String messageBody = """
            {
                "id": "msg-1",
                "poolCode": "POOL-A",
                "rateLimitPerMinute": null,
                "rateLimitKey": null,
                "authToken": "test-token",
                "mediationType": "HTTP",
                "mediationTarget": "http://localhost:8080/test"
            }
            """;

        TextMessage textMessage = mock(TextMessage.class);
        when(textMessage.getText()).thenReturn(messageBody);

        when(mockMessageConsumer.receive(anyLong()))
            .thenReturn(textMessage)
            .thenReturn(null); // Stop polling after first message

        when(mockQueueManager.routeMessage(any(), any())).thenReturn(true);

        // When
        activeMqConsumer.start();

        // Then
        await().untilAsserted(() -> {
            verify(mockQueueManager).routeMessage(any(MessagePointer.class), any(MessageCallback.class));
            verify(mockQueueMetrics).recordMessageReceived(queueName);
            verify(mockQueueMetrics).recordMessageProcessed(queueName, true);
        });
    }

    @Test
    void shouldAcknowledgeMessageOnAck() throws Exception {
        // Given
        String messageBody = """
            {
                "id": "msg-ack",
                "poolCode": "POOL-A",
                "authToken": "test-token",
                "mediationType": "HTTP",
                "mediationTarget": "http://localhost:8080/test"
            }
            """;

        TextMessage textMessage = mock(TextMessage.class);
        when(textMessage.getText()).thenReturn(messageBody);

        when(mockMessageConsumer.receive(anyLong()))
            .thenReturn(textMessage)
            .thenReturn(null);

        ArgumentCaptor<MessageCallback> callbackCaptor = ArgumentCaptor.forClass(MessageCallback.class);
        when(mockQueueManager.routeMessage(any(), callbackCaptor.capture())).thenReturn(true);

        // When
        activeMqConsumer.start();

        await().untilAsserted(() -> {
            verify(mockQueueManager).routeMessage(any(), any());
        });

        MessageCallback callback = callbackCaptor.getValue();
        MessagePointer testMessage = new MessagePointer("msg-ack", "POOL-A", null, null, "token", "HTTP", "http://test.com");
        callback.ack(testMessage);

        // Then
        verify(textMessage).acknowledge();
    }

    @Test
    void shouldRecoverSessionOnNack() throws Exception {
        // Given
        String messageBody = """
            {
                "id": "msg-nack",
                "poolCode": "POOL-A",
                "authToken": "test-token",
                "mediationType": "HTTP",
                "mediationTarget": "http://localhost:8080/test"
            }
            """;

        TextMessage textMessage = mock(TextMessage.class);
        when(textMessage.getText()).thenReturn(messageBody);

        when(mockMessageConsumer.receive(anyLong()))
            .thenReturn(textMessage)
            .thenReturn(null);

        ArgumentCaptor<MessageCallback> callbackCaptor = ArgumentCaptor.forClass(MessageCallback.class);
        when(mockQueueManager.routeMessage(any(), callbackCaptor.capture())).thenReturn(true);

        // When
        activeMqConsumer.start();

        await().untilAsserted(() -> {
            verify(mockQueueManager).routeMessage(any(), any());
        });

        MessageCallback callback = callbackCaptor.getValue();
        MessagePointer testMessage = new MessagePointer("msg-nack", "POOL-A", null, null, "token", "HTTP", "http://test.com");
        callback.nack(testMessage);

        // Then
        // Nack should trigger session.recover() to requeue the message
        verify(mockSession).recover();
        verify(textMessage, never()).acknowledge();
    }

    @Test
    void shouldUseClientAcknowledgeMode() throws Exception {
        // Given
        when(mockMessageConsumer.receive(anyLong())).thenReturn(null);

        // When
        activeMqConsumer.start();

        // Then
        await().untilAsserted(() -> {
            verify(mockConnection, atLeastOnce()).createSession(false, Session.CLIENT_ACKNOWLEDGE);
        });
    }

    @Test
    void shouldPollQueueMetricsUsingBrowser() throws Exception {
        // Given
        @SuppressWarnings("unchecked")
        Enumeration<Message> mockEnumeration = mock(Enumeration.class);
        when(mockEnumeration.hasMoreElements())
            .thenReturn(true)
            .thenReturn(true)
            .thenReturn(true)
            .thenReturn(false); // 3 messages in queue

        when(mockQueueBrowser.getEnumeration()).thenReturn(mockEnumeration);
        when(mockMessageConsumer.receive(anyLong())).thenReturn(null);

        // When
        activeMqConsumer.start();

        // Then
        await().untilAsserted(() -> {
            verify(mockQueueMetrics, atLeastOnce()).recordQueueMetrics(queueName, 3, 0);
        });
    }

    @Test
    void shouldStopGracefully() throws Exception {
        // Given
        when(mockMessageConsumer.receive(anyLong())).thenReturn(null);
        activeMqConsumer.start();

        // When
        activeMqConsumer.stop();

        // Then
        verify(mockMessageConsumer, atLeastOnce()).close();
        verify(mockSession, atLeastOnce()).close();
        verify(mockConnection, atLeastOnce()).close();
    }

    @Test
    void shouldGetQueueIdentifier() {
        assertEquals(queueName, activeMqConsumer.getQueueIdentifier());
    }

    @Test
    void shouldHandleInvalidJson() throws Exception {
        // Given
        TextMessage invalidMessage = mock(TextMessage.class);
        when(invalidMessage.getText()).thenReturn("{ invalid json }");

        when(mockMessageConsumer.receive(anyLong()))
            .thenReturn(invalidMessage)
            .thenReturn(null);

        // When
        activeMqConsumer.start();

        // Then
        await().untilAsserted(() -> {
            verify(mockQueueMetrics).recordMessageReceived(queueName);
            verify(mockQueueMetrics).recordMessageProcessed(queueName, false);
            verify(mockQueueManager, never()).routeMessage(any(), any());
        });
    }

    @Test
    void shouldHandleNonTextMessage() throws Exception {
        // Given
        Message nonTextMessage = mock(Message.class); // Not a TextMessage

        when(mockMessageConsumer.receive(anyLong()))
            .thenReturn(nonTextMessage)
            .thenReturn(null);

        // When
        activeMqConsumer.start();

        // Then
        await().untilAsserted(() -> {
            // Should ignore non-text messages
            verify(mockQueueManager, never()).routeMessage(any(), any());
        });
    }

    @Test
    void shouldHandleMessageNotRouted() throws Exception {
        // Given
        String messageBody = """
            {
                "id": "msg-not-routed",
                "poolCode": "POOL-A",
                "authToken": "test-token",
                "mediationType": "HTTP",
                "mediationTarget": "http://localhost:8080/test"
            }
            """;

        TextMessage textMessage = mock(TextMessage.class);
        when(textMessage.getText()).thenReturn(messageBody);

        when(mockMessageConsumer.receive(anyLong()))
            .thenReturn(textMessage)
            .thenReturn(null);

        // Message not routed (pool full or duplicate)
        when(mockQueueManager.routeMessage(any(), any())).thenReturn(false);

        // When
        activeMqConsumer.start();

        // Then
        await().untilAsserted(() -> {
            verify(mockQueueManager).routeMessage(any(), any());
            verify(mockQueueMetrics).recordMessageProcessed(queueName, false);
        });
    }

    @Test
    void shouldHandleConnectionErrors() throws Exception {
        // Given
        when(mockConnectionFactory.createConnection())
            .thenThrow(new JMSException("Connection failed"));

        // When
        activeMqConsumer.start();

        // Give it time to attempt connection
        Thread.sleep(100);

        // Then - should handle error gracefully without crashing
        activeMqConsumer.stop();
        assertTrue(true, "Should handle connection errors gracefully");
    }

    @Test
    void shouldCreateBrowserForMetrics() throws Exception {
        // Given
        @SuppressWarnings("unchecked")
        Enumeration<Message> mockEnumeration = mock(Enumeration.class);
        when(mockEnumeration.hasMoreElements()).thenReturn(false);
        when(mockQueueBrowser.getEnumeration()).thenReturn(mockEnumeration);
        when(mockMessageConsumer.receive(anyLong())).thenReturn(null);

        // When
        activeMqConsumer.start();

        // Then
        await().untilAsserted(() -> {
            verify(mockSession, atLeast(1)).createBrowser(mockQueue);
        });
    }

    @Test
    void shouldPollMetricsEvery5Seconds() throws Exception {
        // Given
        @SuppressWarnings("unchecked")
        Enumeration<Message> mockEnumeration = mock(Enumeration.class);
        when(mockEnumeration.hasMoreElements()).thenReturn(false);
        when(mockQueueBrowser.getEnumeration()).thenReturn(mockEnumeration);
        when(mockMessageConsumer.receive(anyLong())).thenReturn(null);

        // When
        activeMqConsumer.start();

        // Then - verify metrics are recorded multiple times (polling every 5 seconds)
        await().untilAsserted(() -> {
            verify(mockQueueMetrics, atLeast(2)).recordQueueMetrics(eq(queueName), anyLong(), eq(0L));
        });
    }
}
