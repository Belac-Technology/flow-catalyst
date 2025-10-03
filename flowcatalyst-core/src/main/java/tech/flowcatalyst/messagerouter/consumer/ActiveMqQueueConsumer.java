package tech.flowcatalyst.messagerouter.consumer;

import jakarta.jms.*;
import org.jboss.logging.Logger;
import tech.flowcatalyst.messagerouter.callback.MessageCallback;
import tech.flowcatalyst.messagerouter.manager.QueueManager;
import tech.flowcatalyst.messagerouter.model.MessagePointer;

public class ActiveMqQueueConsumer extends AbstractQueueConsumer {

    private static final Logger LOG = Logger.getLogger(ActiveMqQueueConsumer.class);

    private final ConnectionFactory connectionFactory;
    private final String queueName;

    public ActiveMqQueueConsumer(
            ConnectionFactory connectionFactory,
            String queueName,
            int connections,
            QueueManager queueManager,
            tech.flowcatalyst.messagerouter.metrics.QueueMetricsService queueMetrics) {
        super(queueManager, queueMetrics, connections);
        this.connectionFactory = connectionFactory;
        this.queueName = queueName;
    }

    @Override
    public String getQueueIdentifier() {
        return queueName;
    }

    @Override
    protected void consumeMessages() {
        Connection connection = null;
        Session session = null;
        MessageConsumer consumer = null;

        try {
            connection = connectionFactory.createConnection();
            connection.start();

            session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            Queue queue = session.createQueue(queueName);
            consumer = session.createConsumer(queue);

            while (running.get()) {
                try {
                    Message jmsMessage = consumer.receive(1000); // 1 second timeout
                    if (jmsMessage instanceof TextMessage textMessage) {
                        String messageBody = textMessage.getText();
                        processMessage(
                            messageBody,
                            new ActiveMqMessageCallback(jmsMessage, session)
                        );
                    }
                } catch (Exception e) {
                    LOG.error("Error receiving message from ActiveMQ", e);
                }
            }

        } catch (Exception e) {
            LOG.error("Error in ActiveMQ consumer", e);
        } finally {
            closeResources(consumer, session, connection);
        }
    }

    @Override
    protected void pollQueueMetrics() {
        Connection connection = null;
        Session session = null;
        QueueBrowser browser = null;

        try {
            connection = connectionFactory.createConnection();
            connection.start();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(queueName);
            browser = session.createBrowser(queue);

            while (running.get()) {
                try {
                    // Count pending messages using QueueBrowser
                    long pendingMessages = 0;
                    var enumeration = browser.getEnumeration();
                    while (enumeration.hasMoreElements()) {
                        enumeration.nextElement();
                        pendingMessages++;
                    }

                    // For ActiveMQ, we approximate messagesNotVisible as 0
                    // (would require JMX to get accurate in-flight count)
                    queueMetrics.recordQueueMetrics(queueName, pendingMessages, 0);

                    Thread.sleep(5000); // Poll every 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        LOG.error("Error polling ActiveMQ queue metrics", e);
                        Thread.sleep(5000); // Back off on error
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error initializing ActiveMQ metrics polling", e);
        } finally {
            closeBrowserResources(browser, session, connection);
        }
        LOG.debugf("ActiveMQ queue metrics polling for queue [%s] exited cleanly", queueName);
    }

    private void closeResources(MessageConsumer consumer, Session session, Connection connection) {
        try {
            if (consumer != null) consumer.close();
            if (session != null) session.close();
            if (connection != null) connection.close();
        } catch (Exception e) {
            LOG.error("Error closing ActiveMQ resources", e);
        }
    }

    private void closeBrowserResources(QueueBrowser browser, Session session, Connection connection) {
        try {
            if (browser != null) browser.close();
            if (session != null) session.close();
            if (connection != null) connection.close();
        } catch (Exception e) {
            LOG.error("Error closing ActiveMQ browser resources", e);
        }
    }

    /**
     * Inner class for ActiveMQ-specific message callback
     */
    private static class ActiveMqMessageCallback implements MessageCallback {
        private final Message jmsMessage;
        private final Session session;

        ActiveMqMessageCallback(Message jmsMessage, Session session) {
            this.jmsMessage = jmsMessage;
            this.session = session;
        }

        @Override
        public void ack(MessagePointer message) {
            try {
                jmsMessage.acknowledge();
                LOG.debugf("Acknowledged message [%s] in ActiveMQ", message.id());
            } catch (Exception e) {
                LOG.error("Error acknowledging message in ActiveMQ: " + message.id(), e);
            }
        }

        @Override
        public void nack(MessagePointer message) {
            try {
                // Rollback session to trigger redelivery
                session.recover();
                LOG.debugf("Nacked message [%s] - triggered redelivery", message.id());
            } catch (Exception e) {
                LOG.error("Error nacking message in ActiveMQ: " + message.id(), e);
            }
        }
    }
}
