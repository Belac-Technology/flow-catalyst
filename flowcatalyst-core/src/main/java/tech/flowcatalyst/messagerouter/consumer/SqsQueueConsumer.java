package tech.flowcatalyst.messagerouter.consumer;

import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

import java.util.List;

public class SqsQueueConsumer extends AbstractQueueConsumer {

    private static final Logger LOG = Logger.getLogger(SqsQueueConsumer.class);
    private static final int MAX_MESSAGES_PER_POLL = 10;
    private static final int WAIT_TIME_SECONDS = 20; // Long polling

    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsQueueConsumer(
            SqsClient sqsClient,
            String queueUrl,
            int connections,
            QueueManager queueManager,
            tech.flowcatalyst.messagerouter.metrics.QueueMetricsService queueMetrics) {
        super(queueManager, queueMetrics, connections);
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
    }

    @Override
    public String getQueueIdentifier() {
        return queueUrl;
    }

    @Override
    protected void consumeMessages() {
        while (running.get()) {
            try {
                ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(MAX_MESSAGES_PER_POLL)
                    .waitTimeSeconds(WAIT_TIME_SECONDS)
                    .build();

                // This will block for up to WAIT_TIME_SECONDS
                ReceiveMessageResponse response = sqsClient.receiveMessage(receiveRequest);
                List<Message> messages = response.messages();

                // Process all messages from this poll
                for (Message message : messages) {
                    processMessage(
                        message.body(),
                        new SqsMessageCallback(message.receiptHandle())
                    );
                }

                // After processing messages, check if we should stop polling
                // This allows the current poll to complete before stopping
                if (!running.get()) {
                    LOG.debug("Stop requested, exiting polling loop after completing current batch");
                    break;
                }

            } catch (Exception e) {
                if (running.get()) {
                    LOG.error("Error polling messages from SQS", e);
                    try {
                        Thread.sleep(1000); // Back off on error
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // Shutting down, exit cleanly
                    LOG.debug("Exception during shutdown, exiting cleanly");
                    break;
                }
            }
        }
        LOG.infof("SQS consumer for queue [%s] polling loop exited cleanly", queueUrl);
    }

    /**
     * Periodically poll SQS for queue metrics (pending messages, in-flight messages)
     */
    @Override
    protected void pollQueueMetrics() {
        while (running.get()) {
            try {
                GetQueueAttributesRequest request = GetQueueAttributesRequest.builder()
                    .queueUrl(queueUrl)
                    .attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                    )
                    .build();

                GetQueueAttributesResponse response = sqsClient.getQueueAttributes(request);

                if (response != null && response.attributes() != null) {
                    long pendingMessages = Long.parseLong(
                        response.attributes().getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0")
                    );
                    long messagesNotVisible = Long.parseLong(
                        response.attributes().getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0")
                    );

                    queueMetrics.recordQueueMetrics(queueUrl, pendingMessages, messagesNotVisible);
                }

                // Poll every 5 seconds
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    LOG.error("Error polling SQS queue metrics", e);
                    try {
                        Thread.sleep(5000); // Back off on error
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        LOG.debugf("SQS queue metrics polling for queue [%s] exited cleanly", queueUrl);
    }

    /**
     * Inner class for SQS-specific message callback
     */
    private class SqsMessageCallback implements MessageCallback {
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

                sqsClient.deleteMessage(deleteRequest);
                LOG.debugf("Deleted message [%s] from SQS", message.id());
            } catch (ReceiptHandleIsInvalidException e) {
                // Receipt handle is invalid - message may have already been deleted or visibility timeout expired
                // This is not necessarily an error in distributed systems
                LOG.debugf("Receipt handle invalid for message [%s] - may already be deleted or timeout expired", message.id());
            } catch (Exception e) {
                LOG.errorf(e, "Unexpected error deleting message from SQS: %s", message.id());
            }
        }

        @Override
        public void nack(MessagePointer message) {
            // For SQS, this is a no-op - we rely on visibility timeout
            // Message will become visible again after timeout
            LOG.debugf("Nacked message [%s] - will become visible after timeout", message.id());
        }
    }
}
