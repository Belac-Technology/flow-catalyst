package tech.flowcatalyst.platform.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.flowcatalyst.event.ContextData;
import tech.flowcatalyst.event.Event;
import tech.flowcatalyst.platform.audit.AuditLog;
import tech.flowcatalyst.platform.common.errors.UseCaseError;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

/**
 * MongoDB implementation of {@link UnitOfWork} using multi-document transactions.
 *
 * <p>This implementation ensures atomic commits of:
 * <ul>
 *   <li>Aggregate entity (create/update/delete)</li>
 *   <li>Domain event (in the events collection)</li>
 *   <li>Audit log entry</li>
 * </ul>
 *
 * <p>All three operations occur within a single MongoDB transaction, ensuring
 * consistency. If any operation fails, the entire transaction is rolled back.
 *
 * <p><strong>Requirements:</strong>
 * <ul>
 *   <li>MongoDB 4.0+ (for multi-document transactions)</li>
 *   <li>Replica set deployment (transactions require replica set)</li>
 *   <li>Aggregates must have a public {@code String id} field (TSID)</li>
 *   <li>Aggregates must have a {@code @MongoEntity} annotation or follow naming convention</li>
 * </ul>
 */
@ApplicationScoped
public class MongoTransactionalUnitOfWork implements UnitOfWork {

    @Inject
    MongoClient mongoClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Override
    public <T extends DomainEvent> Result<T> commit(
            Object aggregate,
            T event,
            Object command
    ) {
        try (ClientSession session = mongoClient.startSession()) {
            session.startTransaction();

            try {
                MongoDatabase db = mongoClient.getDatabase(databaseName);

                // 1. Persist/update aggregate
                persistAggregate(session, db, aggregate);

                // 2. Create domain event
                createEvent(session, db, event);

                // 3. Create audit log
                createAuditLog(session, db, event, command);

                session.commitTransaction();
                return Result.success(event);

            } catch (Exception e) {
                session.abortTransaction();
                return Result.failure(new UseCaseError.BusinessRuleViolation(
                    "COMMIT_FAILED",
                    "Failed to commit transaction: " + e.getMessage(),
                    Map.of("exception", e.getClass().getSimpleName())
                ));
            }
        }
    }

    @Override
    public <T extends DomainEvent> Result<T> commitDelete(
            Object aggregate,
            T event,
            Object command
    ) {
        try (ClientSession session = mongoClient.startSession()) {
            session.startTransaction();

            try {
                MongoDatabase db = mongoClient.getDatabase(databaseName);

                // 1. Delete aggregate
                deleteAggregate(session, db, aggregate);

                // 2. Create domain event
                createEvent(session, db, event);

                // 3. Create audit log
                createAuditLog(session, db, event, command);

                session.commitTransaction();
                return Result.success(event);

            } catch (Exception e) {
                session.abortTransaction();
                return Result.failure(new UseCaseError.BusinessRuleViolation(
                    "COMMIT_FAILED",
                    "Failed to commit transaction: " + e.getMessage(),
                    Map.of("exception", e.getClass().getSimpleName())
                ));
            }
        }
    }

    @Override
    public <T extends DomainEvent> Result<T> commitAll(
            List<Object> aggregates,
            T event,
            Object command
    ) {
        try (ClientSession session = mongoClient.startSession()) {
            session.startTransaction();

            try {
                MongoDatabase db = mongoClient.getDatabase(databaseName);

                // 1. Persist/update all aggregates
                for (Object aggregate : aggregates) {
                    persistAggregate(session, db, aggregate);
                }

                // 2. Create domain event
                createEvent(session, db, event);

                // 3. Create audit log
                createAuditLog(session, db, event, command);

                session.commitTransaction();
                return Result.success(event);

            } catch (Exception e) {
                session.abortTransaction();
                return Result.failure(new UseCaseError.BusinessRuleViolation(
                    "COMMIT_FAILED",
                    "Failed to commit transaction: " + e.getMessage(),
                    Map.of("exception", e.getClass().getSimpleName())
                ));
            }
        }
    }

    // ========================================================================
    // Aggregate Operations
    // ========================================================================

    @SuppressWarnings("unchecked")
    private <T> void persistAggregate(ClientSession session, MongoDatabase db, T aggregate) {
        String collectionName = getCollectionName(aggregate.getClass());
        Class<T> clazz = (Class<T>) aggregate.getClass();

        // Get typed collection with POJO codec support
        MongoCollection<T> collection = db.getCollection(collectionName, clazz)
            .withCodecRegistry(getPojoCodecRegistry(db));

        String id = extractId(aggregate);

        // Upsert the document using typed collection
        collection.replaceOne(
            session,
            Filters.eq("_id", id),
            aggregate,
            new ReplaceOptions().upsert(true)
        );
    }

    private void deleteAggregate(ClientSession session, MongoDatabase db, Object aggregate) {
        String collectionName = getCollectionName(aggregate.getClass());
        MongoCollection<Document> collection = db.getCollection(collectionName);

        String id = extractId(aggregate);
        collection.deleteOne(session, Filters.eq("_id", id));
    }

    private CodecRegistry getPojoCodecRegistry(MongoDatabase db) {
        return fromRegistries(
            db.getCodecRegistry(),
            fromProviders(PojoCodecProvider.builder().automatic(true).build())
        );
    }

    // ========================================================================
    // Event Operations
    // ========================================================================

    private void createEvent(ClientSession session, MongoDatabase db, DomainEvent event) {
        // Build context data for searchability
        List<ContextData> contextData = new ArrayList<>();
        contextData.add(new ContextData("principalId", String.valueOf(event.principalId())));
        contextData.add(new ContextData("aggregateType", extractAggregateType(event.subject())));

        Event mongoEvent = new Event(
            event.eventId(),
            event.specVersion(),
            event.eventType(),
            event.source(),
            event.subject(),
            event.time(),
            event.toDataJson(),
            event.correlationId(),
            event.causationId(),
            event.eventType() + "-" + event.eventId(),  // deduplicationId
            event.messageGroup(),
            contextData
        );

        MongoCollection<Event> collection = db.getCollection("events", Event.class)
            .withCodecRegistry(getPojoCodecRegistry(db));
        collection.insertOne(session, mongoEvent);
    }

    // ========================================================================
    // Audit Log Operations
    // ========================================================================

    private void createAuditLog(ClientSession session, MongoDatabase db, DomainEvent event, Object command)
            throws JsonProcessingException {
        AuditLog log = new AuditLog();
        log.id = TsidGenerator.generate();
        log.entityType = extractAggregateType(event.subject());
        log.entityId = extractEntityIdFromSubject(event.subject());
        log.operation = command.getClass().getSimpleName();
        log.operationJson = objectMapper.writeValueAsString(command);
        log.principalId = event.principalId();
        log.performedAt = event.time();

        MongoCollection<AuditLog> collection = db.getCollection("audit_logs", AuditLog.class)
            .withCodecRegistry(getPojoCodecRegistry(db));
        collection.insertOne(session, log);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Get the MongoDB collection name for an aggregate class.
     *
     * <p>Uses snake_case plural of class name as convention.
     */
    private String getCollectionName(Class<?> aggregateClass) {
        // Use convention: EventType -> event_types
        String name = aggregateClass.getSimpleName();
        return toSnakeCase(name) + "s";
    }

    private String toSnakeCase(String camelCase) {
        return camelCase
            .replaceAll("([a-z])([A-Z])", "$1_$2")
            .toLowerCase();
    }

    /**
     * Extract the ID from an aggregate using reflection.
     *
     * <p>Supports both:
     * <ul>
     *   <li>Classes with a public {@code String id} field (TSID)</li>
     *   <li>Records with an {@code id()} accessor method</li>
     * </ul>
     */
    private String extractId(Object aggregate) {
        Class<?> clazz = aggregate.getClass();

        // First try: record accessor method id()
        if (clazz.isRecord()) {
            try {
                var method = clazz.getMethod("id");
                return (String) method.invoke(aggregate);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                    "Record must have an id() accessor returning String: " + clazz.getName(),
                    e
                );
            }
        }

        // Second try: public field for traditional classes
        try {
            Field field = clazz.getField("id");
            return (String) field.get(aggregate);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalArgumentException(
                "Aggregate must have a public String id field: " + clazz.getName(),
                e
            );
        }
    }

    /**
     * Extract the aggregate type from a subject string.
     *
     * <p>Subject format: "platform.eventtype.123456789"
     * <p>Returns: "EventType" (capitalized)
     */
    private String extractAggregateType(String subject) {
        if (subject == null) {
            return "Unknown";
        }
        String[] parts = subject.split("\\.");
        if (parts.length >= 2) {
            return capitalize(parts[1]);
        }
        return "Unknown";
    }

    /**
     * Extract the entity ID from a subject string.
     *
     * <p>Subject format: "platform.eventtype.123456789"
     * <p>Returns: 123456789
     */
    private String extractEntityIdFromSubject(String subject) {
        if (subject == null) {
            return null;
        }
        String[] parts = subject.split("\\.");
        if (parts.length >= 3) {
            try {
                return parts[2];
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
