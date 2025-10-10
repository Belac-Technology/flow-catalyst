package tech.flowcatalyst.messagerouter.health;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;
import tech.flowcatalyst.messagerouter.config.QueueType;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for BrokerHealthService covering:
 * - SQS connectivity checks
 * - ActiveMQ connectivity checks
 * - Queue accessibility checks
 * - Metrics tracking
 * - Error handling
 */
@QuarkusTest
class BrokerHealthServiceTest {

    private BrokerHealthService brokerHealthService;
    private SqsClient mockSqsClient;
    private ConnectionFactory mockConnectionFactory;
    private MeterRegistry mockMeterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        brokerHealthService = new BrokerHealthService();

        // Create mocks
        mockSqsClient = mock(SqsClient.class);
        mockConnectionFactory = mock(ConnectionFactory.class);
        mockMeterRegistry = mock(MeterRegistry.class);

        // Configure meter registry to return mock counters
        Counter mockCounter = mock(Counter.class);
        when(mockMeterRegistry.counter(anyString())).thenReturn(mockCounter);

        Counter.Builder mockCounterBuilder = mock(Counter.Builder.class);
        when(mockCounterBuilder.description(anyString())).thenReturn(mockCounterBuilder);
        when(mockCounterBuilder.register(any())).thenReturn(mockCounter);
        when(Counter.builder(anyString())).thenReturn(mockCounterBuilder);

        // Mock gauge registration
        when(mockMeterRegistry.gauge(anyString(), any(AtomicInteger.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

        // Inject mocks using reflection
        setPrivateField(brokerHealthService, "sqsClient", mockSqsClient);
        setPrivateField(brokerHealthService, "connectionFactory", mockConnectionFactory);
        setPrivateField(brokerHealthService, "meterRegistry", mockMeterRegistry);
        setPrivateField(brokerHealthService, "messageRouterEnabled", true);
    }

    @Test
    void shouldPassSqsConnectivityCheck() throws Exception {
        // Given
        setPrivateField(brokerHealthService, "queueType", QueueType.SQS);
        when(mockSqsClient.listQueues(any(ListQueuesRequest.class)))
            .thenReturn(ListQueuesResponse.builder().build());

        brokerHealthService.init();

        // When
        List<String> issues = brokerHealthService.checkBrokerConnectivity();

        // Then
        assertTrue(issues.isEmpty(), "Should have no issues");
        verify(mockSqsClient).listQueues(any(ListQueuesRequest.class));
    }

    @Test
    void shouldFailSqsConnectivityCheckOnException() throws Exception {
        // Given
        setPrivateField(brokerHealthService, "queueType", QueueType.SQS);
        when(mockSqsClient.listQueues(any(ListQueuesRequest.class)))
            .thenThrow(new RuntimeException("Connection failed"));

        brokerHealthService.init();

        // When
        List<String> issues = brokerHealthService.checkBrokerConnectivity();

        // Then
        assertFalse(issues.isEmpty(), "Should have issues");
        assertTrue(issues.get(0).contains("SQS"), "Issue should mention SQS");
    }

    @Test
    void shouldPassActiveMqConnectivityCheck() throws Exception {
        // Given
        setPrivateField(brokerHealthService, "queueType", QueueType.ACTIVEMQ);

        Connection mockConnection = mock(Connection.class);
        when(mockConnectionFactory.createConnection()).thenReturn(mockConnection);

        brokerHealthService.init();

        // When
        List<String> issues = brokerHealthService.checkBrokerConnectivity();

        // Then
        assertTrue(issues.isEmpty(), "Should have no issues");
        verify(mockConnectionFactory).createConnection();
        verify(mockConnection).start();
        verify(mockConnection).close();
    }

    @Test
    void shouldFailActiveMqConnectivityCheckOnException() throws Exception {
        // Given
        setPrivateField(brokerHealthService, "queueType", QueueType.ACTIVEMQ);
        when(mockConnectionFactory.createConnection())
            .thenThrow(new RuntimeException("Broker unavailable"));

        brokerHealthService.init();

        // When
        List<String> issues = brokerHealthService.checkBrokerConnectivity();

        // Then
        assertFalse(issues.isEmpty(), "Should have issues");
        assertTrue(issues.get(0).contains("ACTIVEMQ"), "Issue should mention ActiveMQ");
    }

    @Test
    void shouldCheckSqsQueueAccessibility() {
        // Given
        String queueName = "test-queue";
        String queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-queue";

        when(mockSqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
            .thenReturn(GetQueueUrlResponse.builder()
                .queueUrl(queueUrl)
                .build());

        // When
        List<String> issues = brokerHealthService.checkSqsQueueAccessible(queueName);

        // Then
        assertTrue(issues.isEmpty(), "Should have no issues");
        verify(mockSqsClient).getQueueUrl(any(GetQueueUrlRequest.class));
    }

    @Test
    void shouldFailSqsQueueAccessibilityCheckWhenQueueNotFound() {
        // Given
        String queueName = "non-existent-queue";
        when(mockSqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
            .thenThrow(new RuntimeException("Queue does not exist"));

        // When
        List<String> issues = brokerHealthService.checkSqsQueueAccessible(queueName);

        // Then
        assertFalse(issues.isEmpty(), "Should have issues");
        assertTrue(issues.get(0).contains(queueName), "Issue should mention queue name");
    }

    @Test
    void shouldHandleEmptyQueueUrl() {
        // Given
        String queueName = "test-queue";
        when(mockSqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
            .thenReturn(GetQueueUrlResponse.builder()
                .queueUrl("")
                .build());

        // When
        List<String> issues = brokerHealthService.checkSqsQueueAccessible(queueName);

        // Then
        assertFalse(issues.isEmpty(), "Should have issues for empty URL");
        assertTrue(issues.get(0).contains("empty"), "Issue should mention empty URL");
    }

    @Test
    void shouldSkipCheckWhenMessageRouterDisabled() throws Exception {
        // Given
        setPrivateField(brokerHealthService, "messageRouterEnabled", false);
        setPrivateField(brokerHealthService, "queueType", QueueType.SQS);

        // When
        List<String> issues = brokerHealthService.checkBrokerConnectivity();

        // Then
        assertTrue(issues.isEmpty(), "Should skip check when disabled");
        verify(mockSqsClient, never()).listQueues(any(ListQueuesRequest.class));
    }

    @Test
    void shouldCloseConnectionEvenOnError() throws Exception {
        // Given
        setPrivateField(brokerHealthService, "queueType", QueueType.ACTIVEMQ);

        Connection mockConnection = mock(Connection.class);
        when(mockConnectionFactory.createConnection()).thenReturn(mockConnection);
        doThrow(new RuntimeException("Start failed")).when(mockConnection).start();

        brokerHealthService.init();

        // When
        List<String> issues = brokerHealthService.checkBrokerConnectivity();

        // Then
        assertFalse(issues.isEmpty(), "Should have issues");
        verify(mockConnection).close(); // Connection should still be closed
    }

    @Test
    void shouldReturnBrokerType() throws Exception {
        // Given
        setPrivateField(brokerHealthService, "queueType", QueueType.SQS);

        // When
        QueueType brokerType = brokerHealthService.getBrokerType();

        // Then
        assertEquals(QueueType.SQS, brokerType);
    }

    // Helper method to set private fields using reflection
    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
