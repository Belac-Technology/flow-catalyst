package tech.flowcatalyst.queue;

import java.util.Optional;

/**
 * Configuration for a queue publisher.
 *
 * @param queueType The type of queue (SQS, ActiveMQ, Embedded)
 * @param queueUrl Queue URL (for SQS) or queue name (for others)
 * @param fifoEnabled Whether FIFO ordering is enabled (for SQS)
 * @param deduplicationEnabled Whether content-based deduplication is enabled
 * @param maxBatchSize Maximum batch size for publishing (SQS max is 10)
 * @param embeddedDbPath Path for embedded SQLite database (embedded only)
 */
public record QueueConfig(
    QueueType queueType,
    String queueUrl,
    boolean fifoEnabled,
    boolean deduplicationEnabled,
    int maxBatchSize,
    Optional<String> embeddedDbPath
) {
    /**
     * Create config for SQS FIFO queue.
     */
    public static QueueConfig sqsFifo(String queueUrl) {
        return new QueueConfig(
            QueueType.SQS,
            queueUrl,
            true,
            true,
            10,
            Optional.empty()
        );
    }

    /**
     * Create config for SQS standard queue.
     */
    public static QueueConfig sqsStandard(String queueUrl) {
        return new QueueConfig(
            QueueType.SQS,
            queueUrl,
            false,
            false,
            10,
            Optional.empty()
        );
    }

    /**
     * Create config for ActiveMQ queue.
     */
    public static QueueConfig activeMq(String queueName) {
        return new QueueConfig(
            QueueType.ACTIVEMQ,
            queueName,
            true,  // Always supports message groups
            false,
            100,
            Optional.empty()
        );
    }

    /**
     * Create config for embedded SQLite queue.
     */
    public static QueueConfig embedded(String dbPath) {
        return new QueueConfig(
            QueueType.EMBEDDED,
            "embedded",
            true,
            true,
            100,
            Optional.of(dbPath)
        );
    }

    /**
     * Create config for in-memory embedded queue (for testing).
     */
    public static QueueConfig embeddedInMemory() {
        return new QueueConfig(
            QueueType.EMBEDDED,
            "embedded",
            true,
            true,
            100,
            Optional.of(":memory:")
        );
    }
}
