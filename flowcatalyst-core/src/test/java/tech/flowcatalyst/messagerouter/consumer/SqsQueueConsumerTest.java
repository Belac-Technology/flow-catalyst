package tech.flowcatalyst.messagerouter.consumer;

import io.quarkus.test.common.QuarkusTestResource;
import tech.flowcatalyst.messagerouter.integration.PostgresTestResource;
import io.quarkus.test.junit.QuarkusTest;
import tech.flowcatalyst.messagerouter.integration.PostgresTestResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.metrics.QueueMetricsService;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class SqsQueueConsumerTest {

    private SqsQueueConsumer sqsConsumer;
    private SqsClient mockSqsClient;
    private QueueManager mockQueueManager;
    private QueueMetricsService mockQueueMetrics;

    private final String queueUrl = "https://sqs.us-east-1.amazonaws.com/123456789/test-queue";

    @BeforeEach
    void setUp() {
        mockSqsClient = mock(SqsClient.class);
        mockQueueManager = mock(QueueManager.class);
        mockQueueMetrics = mock(QueueMetricsService.class);

        sqsConsumer = new SqsQueueConsumer(
            mockSqsClient,
            queueUrl,
            1, // 1 connection
            mockQueueManager,
            mockQueueMetrics
        );
    }

    @AfterEach
    void tearDown() {
        if (sqsConsumer != null) {
            sqsConsumer.stop();
        }
    }

    @Test
    void shouldPollAndProcessMessages() {
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

        Message sqsMessage = Message.builder()
            .body(messageBody)
            .receiptHandle("receipt-123")
            .build();

        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
            .messages(sqsMessage)
            .build();

        when(mockSqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(response)
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build()); // Empty on second call

        when(mockQueueManager.routeMessage(any(), any())).thenReturn(true);

        // When
        sqsConsumer.start();

        // Then
        await().untilAsserted(() -> {
            verify(mockQueueManager).routeMessage(any(MessagePointer.class), any(MessageCallback.class));
            verify(mockQueueMetrics).recordMessageReceived(queueUrl);
        });
    }

    @Test
    void shouldDeleteMessageOnAck() {
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

        String receiptHandle = "receipt-ack-123";

        Message sqsMessage = Message.builder()
            .body(messageBody)
            .receiptHandle(receiptHandle)
            .build();

        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
            .messages(sqsMessage)
            .build();

        when(mockSqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(response)
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        ArgumentCaptor<MessageCallback> callbackCaptor = ArgumentCaptor.forClass(MessageCallback.class);
        when(mockQueueManager.routeMessage(any(), callbackCaptor.capture())).thenReturn(true);

        // When
        sqsConsumer.start();

        await().untilAsserted(() -> {
            verify(mockQueueManager).routeMessage(any(), any());
        });

        MessageCallback callback = callbackCaptor.getValue();
        MessagePointer testMessage = new MessagePointer("msg-ack", "POOL-A", null, null, "token", "HTTP", "http://test.com");
        callback.ack(testMessage);

        // Then
        ArgumentCaptor<DeleteMessageRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(mockSqsClient).deleteMessage(deleteCaptor.capture());
        assertEquals(queueUrl, deleteCaptor.getValue().queueUrl());
        assertEquals(receiptHandle, deleteCaptor.getValue().receiptHandle());
    }

    @Test
    void shouldNotDeleteMessageOnNack() {
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

        Message sqsMessage = Message.builder()
            .body(messageBody)
            .receiptHandle("receipt-nack-123")
            .build();

        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
            .messages(sqsMessage)
            .build();

        when(mockSqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(response)
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        ArgumentCaptor<MessageCallback> callbackCaptor = ArgumentCaptor.forClass(MessageCallback.class);
        when(mockQueueManager.routeMessage(any(), callbackCaptor.capture())).thenReturn(true);

        // When
        sqsConsumer.start();

        await().untilAsserted(() -> {
            verify(mockQueueManager).routeMessage(any(), any());
        });

        MessageCallback callback = callbackCaptor.getValue();
        MessagePointer testMessage = new MessagePointer("msg-nack", "POOL-A", null, null, "token", "HTTP", "http://test.com");
        callback.nack(testMessage);

        // Then
        // Nack should NOT delete the message (relies on visibility timeout)
        verify(mockSqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void shouldUseLongPolling() {
        // Given
        when(mockSqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        // When
        sqsConsumer.start();

        // Then
        await().untilAsserted(() -> {
            ArgumentCaptor<ReceiveMessageRequest> requestCaptor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
            verify(mockSqsClient, atLeastOnce()).receiveMessage(requestCaptor.capture());

            ReceiveMessageRequest request = requestCaptor.getValue();
            assertEquals(queueUrl, request.queueUrl());
            assertEquals(20, request.waitTimeSeconds()); // Long polling
            assertEquals(10, request.maxNumberOfMessages());
        });
    }

    @Test
    void shouldStopGracefully() {
        // Given
        when(mockSqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        sqsConsumer.start();

        // When
        sqsConsumer.stop();

        // Then - just verify it doesn't throw an exception
        assertTrue(true, "Stop should complete without errors");
    }

    @Test
    void shouldGetQueueIdentifier() {
        assertEquals(queueUrl, sqsConsumer.getQueueIdentifier());
    }

    @Test
    void shouldHandleInvalidJson() {
        // Given
        Message invalidMessage = Message.builder()
            .body("{ invalid json }")
            .receiptHandle("receipt-invalid")
            .build();

        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
            .messages(invalidMessage)
            .build();

        when(mockSqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(response)
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        // When
        sqsConsumer.start();

        // Then
        await().untilAsserted(() -> {
            verify(mockQueueMetrics).recordMessageReceived(queueUrl);
            verify(mockQueueMetrics).recordMessageProcessed(queueUrl, false);
            verify(mockQueueManager, never()).routeMessage(any(), any());
        });
    }
}
