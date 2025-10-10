package tech.flowcatalyst.messagerouter.factory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import tech.flowcatalyst.messagerouter.config.QueueConfig;
import tech.flowcatalyst.messagerouter.config.QueueType;
import tech.flowcatalyst.messagerouter.consumer.ActiveMqQueueConsumer;
import tech.flowcatalyst.messagerouter.consumer.QueueConsumer;
import tech.flowcatalyst.messagerouter.consumer.SqsQueueConsumer;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.metrics.QueueMetricsService;
import tech.flowcatalyst.messagerouter.warning.WarningService;

@ApplicationScoped
public class QueueConsumerFactoryImpl implements QueueConsumerFactory {

    private static final Logger LOG = Logger.getLogger(QueueConsumerFactoryImpl.class);

    @ConfigProperty(name = "message-router.queue-type")
    QueueType queueType;

    @ConfigProperty(name = "message-router.sqs.max-messages-per-poll")
    int sqsMaxMessagesPerPoll;

    @ConfigProperty(name = "message-router.sqs.wait-time-seconds")
    int sqsWaitTimeSeconds;

    @ConfigProperty(name = "message-router.activemq.receive-timeout-ms")
    int activemqReceiveTimeoutMs;

    @ConfigProperty(name = "message-router.metrics.poll-interval-seconds")
    int metricsPollIntervalSeconds;

    @Inject
    QueueManager queueManager;

    @Inject
    QueueMetricsService queueMetrics;

    @Inject
    WarningService warningService;

    @Inject
    SqsClient sqsClient;

    @Inject
    ConnectionFactory connectionFactory;

    @Override
    public QueueConsumer createConsumer(QueueConfig queueConfig, int connections) {
        LOG.infof("Creating %s consumer with %d connections", queueType, connections);

        return switch (queueType) {
            case SQS -> {
                String queueUrl = queueConfig.queueUri() != null
                    ? queueConfig.queueUri()
                    : queueConfig.queueName();
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
        };
    }
}
