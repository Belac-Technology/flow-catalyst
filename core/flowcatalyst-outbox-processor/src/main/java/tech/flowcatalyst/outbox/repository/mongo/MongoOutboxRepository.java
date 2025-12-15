package tech.flowcatalyst.outbox.repository.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;
import tech.flowcatalyst.outbox.config.OutboxProcessorConfig;
import tech.flowcatalyst.outbox.model.OutboxItem;
import tech.flowcatalyst.outbox.model.OutboxItemType;
import tech.flowcatalyst.outbox.model.OutboxStatus;
import tech.flowcatalyst.outbox.repository.OutboxRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MongoDB implementation of OutboxRepository.
 * Uses findOneAndUpdate for atomic fetch-and-lock operations.
 */
@ApplicationScoped
public class MongoOutboxRepository implements OutboxRepository {

    private static final Logger LOG = Logger.getLogger(MongoOutboxRepository.class);

    @Inject
    MongoClient mongoClient;

    @Inject
    OutboxProcessorConfig config;

    @Override
    public List<OutboxItem> fetchAndLockPending(OutboxItemType type, int limit) {
        MongoCollection<Document> collection = getCollection(type);
        List<OutboxItem> items = new ArrayList<>();

        // Use findOneAndUpdate in a loop for atomic fetch-and-lock
        // Sort by messageGroup, createdAt for FIFO ordering
        Bson filter = Filters.eq("status", "PENDING");
        Bson sort = Sorts.ascending("messageGroup", "createdAt");
        Bson update = Updates.combine(
            Updates.set("status", "PROCESSING"),
            Updates.set("processedAt", new Date())
        );

        FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
            .sort(sort)
            .returnDocument(ReturnDocument.AFTER);

        for (int i = 0; i < limit; i++) {
            Document doc = collection.findOneAndUpdate(filter, update, options);
            if (doc == null) {
                break; // No more pending items
            }
            items.add(mapDocument(doc, type));
        }

        return items;
    }

    @Override
    public void markCompleted(OutboxItemType type, List<String> ids) {
        if (ids.isEmpty()) return;

        MongoCollection<Document> collection = getCollection(type);

        Bson filter = Filters.in("_id", ids);
        Bson update = Updates.combine(
            Updates.set("status", "COMPLETED"),
            Updates.set("processedAt", new Date())
        );

        long updated = collection.updateMany(filter, update).getModifiedCount();
        LOG.debugf("Marked %d items as COMPLETED in %s", updated, getCollectionName(type));
    }

    @Override
    public void markFailed(OutboxItemType type, List<String> ids, String errorMessage) {
        if (ids.isEmpty()) return;

        MongoCollection<Document> collection = getCollection(type);

        Bson filter = Filters.in("_id", ids);
        Bson update = Updates.combine(
            Updates.set("status", "FAILED"),
            Updates.set("errorMessage", errorMessage),
            Updates.set("processedAt", new Date())
        );

        long updated = collection.updateMany(filter, update).getModifiedCount();
        LOG.debugf("Marked %d items as FAILED in %s", updated, getCollectionName(type));
    }

    @Override
    public void scheduleRetry(OutboxItemType type, List<String> ids) {
        if (ids.isEmpty()) return;

        MongoCollection<Document> collection = getCollection(type);

        Bson filter = Filters.and(
            Filters.in("_id", ids),
            Filters.lt("retryCount", config.maxRetries())
        );
        Bson update = Updates.combine(
            Updates.set("status", "PENDING"),
            Updates.inc("retryCount", 1),
            Updates.unset("processedAt")
        );

        long updated = collection.updateMany(filter, update).getModifiedCount();
        LOG.debugf("Scheduled %d items for retry in %s", updated, getCollectionName(type));
    }

    @Override
    public int recoverStuckItems(OutboxItemType type, int timeoutSeconds) {
        MongoCollection<Document> collection = getCollection(type);

        // Calculate timeout threshold
        Date threshold = new Date(System.currentTimeMillis() - (timeoutSeconds * 1000L));

        Bson filter = Filters.and(
            Filters.eq("status", "PROCESSING"),
            Filters.lt("processedAt", threshold)
        );
        Bson update = Updates.combine(
            Updates.set("status", "PENDING"),
            Updates.unset("processedAt")
        );

        long recovered = collection.updateMany(filter, update).getModifiedCount();

        if (recovered > 0) {
            LOG.infof("Recovered %d stuck items in %s", recovered, getCollectionName(type));
        }
        return (int) recovered;
    }

    private MongoCollection<Document> getCollection(OutboxItemType type) {
        return mongoClient
            .getDatabase(config.mongoDatabase())
            .getCollection(getCollectionName(type));
    }

    private String getCollectionName(OutboxItemType type) {
        return type == OutboxItemType.EVENT ? config.eventsTable() : config.dispatchJobsTable();
    }

    private OutboxItem mapDocument(Document doc, OutboxItemType type) {
        return new OutboxItem(
            doc.getString("_id"),
            type,
            doc.getString("messageGroup"),
            doc.getString("payload"),
            OutboxStatus.valueOf(doc.getString("status")),
            doc.getInteger("retryCount", 0),
            toInstant(doc.getDate("createdAt")),
            toInstant(doc.getDate("processedAt")),
            doc.getString("errorMessage")
        );
    }

    private Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }
}
