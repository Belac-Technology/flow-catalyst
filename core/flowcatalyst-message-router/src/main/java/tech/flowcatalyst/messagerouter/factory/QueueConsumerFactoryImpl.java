package tech.flowcatalyst.messagerouter.factory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import tech.flowcatalyst.messagerouter.config.QueueConfig;
import tech.flowcatalyst.messagerouter.config.QueueType;
import tech.flowcatalyst.messagerouter.config.SqsConsumerMode;
import io.agroal.api.AgroalDataSource;
import tech.flowcatalyst.messagerouter.consumer.ActiveMqQueueConsumer;
import tech.flowcatalyst.messagerouter.consumer.AsyncSqsQueueConsumer;
import tech.flowcatalyst.messagerouter.consumer.QueueConsumer;
import tech.flowcatalyst.messagerouter.consumer.SqsQueueConsumer;
import tech.flowcatalyst.messagerouter.embedded.EmbeddedQueueConsumer;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.metrics.QueueMetricsService;
import tech.flowcatalyst.messagerouter.warning.WarningService;

@ApplicationScoped
public class QueueConsumerFactoryImpl implements QueueConsumerFactory {

    private static final Logger LOG = Logger.getLogger(QueueConsumerFactoryImpl.class);

    @ConfigProperty(name = "message-router.queue-type")
    QueueType queueType;

    @ConfigProperty(name = "message-router.sqs.consumer-mode", defaultValue = "ASYNC")
    SqsConsumerMode sqsConsumerMode;

    @ConfigProperty(name = "message-router.sqs.max-messages-per-poll")
    int sqsMaxMessagesPerPoll;

    @ConfigProperty(name = "message-router.sqs.wait-time-seconds")
    int sqsWaitTimeSeconds;

    @ConfigProperty(name = "message-router.activemq.receive-timeout-ms")
    int activemqReceiveTimeoutMs;

    @ConfigProperty(name = "message-router.metrics.poll-interval-seconds")
    int metricsPollIntervalSeconds;

    @ConfigProperty(name = "message-router.embedded.visibility-timeout-seconds", defaultValue = "30")
    int embeddedVisibilityTimeoutSeconds;

    @ConfigProperty(name = "message-router.embedded.receive-timeout-ms", defaultValue = "1000")
    int embeddedReceiveTimeoutMs;

    @Inject
    QueueManager queueManager;

    @Inject
    QueueMetricsService queueMetrics;

    @Inject
    WarningService warningService;

    @Inject
    SqsClient sqsClient;

    @Inject
    SqsAsyncClient sqsAsyncClient;

    @Inject
    ConnectionFactory connectionFactory;

    @Inject
    @io.quarkus.agroal.DataSource("embedded-queue")
    AgroalDataSource embeddedQueueDataSource;

    @Override
    public QueueConsumer createConsumer(QueueConfig queueConfig, int connections) {
        LOG.infof("Creating %s consumer with %d connections", queueType, connections);

        return switch (queueType) {
            case SQS -> {
                String queueUrl = queueConfig.queueUri() != null
                    ? queueConfig.queueUri()
                    : queueConfig.queueName();

                if (sqsConsumerMode == SqsConsumerMode.ASYNC) {
                    LOG.infof("Using ASYNC SQS consumer for queue [%s]", queueUrl);
                    yield new AsyncSqsQueueConsumer(
                        sqsAsyncClient,
                        queueUrl,
                        connections,
                        queueManager,
                        queueMetrics,
                        warningService,
                        sqsMaxMessagesPerPoll,
                        sqsWaitTimeSeconds,
                        metricsPollIntervalSeconds
                    );
                } else {
                    LOG.infof("Using SYNC SQS consumer for queue [%s]", queueUrl);
                    yield new SqsQueueConsumer(
                        sqsClient,
                        queueUrl,
                        connections,
                        queueManager,
                        queueMetrics,
                        warningService,
                        sqsMaxMessagesPerPoll,
                        sqsWaitTimeSeconds,
                        metricsPollIntervalSeconds
                    );
                }
            }
            case ACTIVEMQ -> {
                String queueName = queueConfig.queueName();
                yield new ActiveMqQueueConsumer(
                    connectionFactory,
                    queueName,
                    connections,
                    queueManager,
                    queueMetrics,
                    warningService,
                    activemqReceiveTimeoutMs,
                    metricsPollIntervalSeconds
                );
            }
            case EMBEDDED -> {
                String queueName = queueConfig.queueName();
                LOG.infof("Using Embedded SQLite Queue consumer for queue [%s]", queueName);
                yield new EmbeddedQueueConsumer(
                    embeddedQueueDataSource,
                    queueName,
                    connections,
                    queueManager,
                    queueMetrics,
                    warningService,
                    embeddedVisibilityTimeoutSeconds,
                    embeddedReceiveTimeoutMs
                );
            }
        };
    }
}
