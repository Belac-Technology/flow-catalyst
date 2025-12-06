package tech.flowcatalyst.event;

import io.quarkus.mongodb.panache.common.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;
import java.time.Instant;
import java.util.List;

/**
 * Event document stored in MongoDB, based on CloudEvents specification.
 *
 * This is the core event structure for the FlowCatalyst event store.
 * Events are immutable once created and are designed for high-volume
 * write operations.
 *
 * CloudEvents spec: https://cloudevents.io/
 */
@MongoEntity(collection = "events")
public class Event extends PanacheMongoEntityBase {

    /**
     * CloudEvents spec version we're implementing.
     */
    public static final String CLOUDEVENTS_SPEC_VERSION = "1.0";

    /**
     * TSID - unique identifier for the event
     */
    @BsonId
    public Long id;

    /**
     * The version that corresponds to the event type spec version
     */
    public String specVersion;

    /**
     * The string identifier from the event type (e.g., "inmotion:execution:trip:started")
     */
    public String type;

    /**
     * Normally our app identifier but could be any source URI
     */
    public String source;

    /**
     * Qualified aggregate ID (e.g., "inmotion.execution.trip.1234")
     */
    public String subject;

    /**
     * When the event occurred
     */
    public Instant time;

    /**
     * String representation of the event data (JSON, protobuf, etc.)
     */
    public String data;

    /**
     * Optional correlation ID for tracing related events
     */
    public String correlationId;

    /**
     * Optional ID of the event that caused this event
     */
    public String causationId;

    /**
     * Optional string for deduplication (idempotency key)
     */
    public String deduplicationId;

    /**
     * Message group for ordering guarantees
     */
    public String messageGroup;

    /**
     * List of key-value pairs for search and filtering
     */
    public List<ContextData> contextData;

    public Event() {
    }

    public Event(Long id, String specVersion, String type, String source, String subject,
                 Instant time, String data, String correlationId, String causationId,
                 String deduplicationId, String messageGroup, List<ContextData> contextData) {
        this.id = id;
        this.specVersion = specVersion;
        this.type = type;
        this.source = source;
        this.subject = subject;
        this.time = time;
        this.data = data;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.deduplicationId = deduplicationId;
        this.messageGroup = messageGroup;
        this.contextData = contextData;
    }

    // Accessor methods for compatibility with record-style usage
    public Long id() { return id; }
    public String specVersion() { return specVersion; }
    public String type() { return type; }
    public String source() { return source; }
    public String subject() { return subject; }
    public Instant time() { return time; }
    public String data() { return data; }
    public String correlationId() { return correlationId; }
    public String causationId() { return causationId; }
    public String deduplicationId() { return deduplicationId; }
    public String messageGroup() { return messageGroup; }
    public List<ContextData> contextData() { return contextData; }
}
