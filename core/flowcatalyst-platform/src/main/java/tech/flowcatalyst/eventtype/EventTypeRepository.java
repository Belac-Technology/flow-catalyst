package tech.flowcatalyst.eventtype;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Repository for EventType entities.
 * EventTypes are global (not tenant-scoped).
 *
 * Event type codes follow the format: application:subdomain:aggregate:action
 */
@ApplicationScoped
public class EventTypeRepository implements PanacheMongoRepositoryBase<EventType, String> {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> getDocumentCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("event_types");
    }

    /**
     * Find an event type by its unique code.
     */
    public Optional<EventType> findByCode(String code) {
        return find("code", code).firstResultOptional();
    }

    /**
     * Check if a code already exists.
     */
    public boolean existsByCode(String code) {
        return count("code", code) > 0;
    }

    /**
     * Find all event types ordered by code.
     */
    public List<EventType> findAllOrdered() {
        return listAll(Sort.by("code"));
    }

    /**
     * Find all current (non-archived) event types.
     */
    public List<EventType> findCurrent() {
        return list("status", Sort.by("code"), EventTypeStatus.CURRENT);
    }

    /**
     * Find all archived event types.
     */
    public List<EventType> findArchived() {
        return list("status", Sort.by("code"), EventTypeStatus.ARCHIVE);
    }

    /**
     * Find event types by code prefix (e.g., all events for an application).
     */
    public List<EventType> findByCodePrefix(String prefix) {
        // Use regex for prefix matching in MongoDB
        return list("code like ?1", prefix + "%");
    }

    /**
     * Get distinct application names (first segment of code).
     * Code format: application:subdomain:aggregate:action
     */
    public List<String> findDistinctApplications() {
        MongoCollection<Document> collection = getDocumentCollection();

        // Use aggregation to extract first segment and get distinct values
        List<Document> pipeline = List.of(
            new Document("$project", new Document("application",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 0)))),
            new Document("$group", new Document("_id", "$application")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            String app = doc.getString("_id");
            if (app != null && !app.isEmpty()) {
                results.add(app);
            }
        }
        return results;
    }

    /**
     * Get distinct subdomains for a given application.
     */
    public List<String> findDistinctSubdomains(String application) {
        MongoCollection<Document> collection = getDocumentCollection();
        String prefix = application + ":";

        List<Document> pipeline = List.of(
            new Document("$match", new Document("code",
                new Document("$regex", "^" + Pattern.quote(prefix)))),
            new Document("$project", new Document("subdomain",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 1)))),
            new Document("$group", new Document("_id", "$subdomain")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            String subdomain = doc.getString("_id");
            if (subdomain != null && !subdomain.isEmpty()) {
                results.add(subdomain);
            }
        }
        return results;
    }

    /**
     * Get all distinct subdomains across all applications (second segment of code).
     */
    public List<String> findAllDistinctSubdomains() {
        MongoCollection<Document> collection = getDocumentCollection();

        List<Document> pipeline = List.of(
            new Document("$project", new Document("subdomain",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 1)))),
            new Document("$group", new Document("_id", "$subdomain")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            String subdomain = doc.getString("_id");
            if (subdomain != null && !subdomain.isEmpty()) {
                results.add(subdomain);
            }
        }
        return results;
    }

    /**
     * Get distinct subdomains optionally filtered by applications.
     */
    public List<String> findDistinctSubdomains(List<String> applications) {
        if (applications == null || applications.isEmpty()) {
            return findAllDistinctSubdomains();
        }
        if (applications.size() == 1) {
            return findDistinctSubdomains(applications.get(0));
        }

        MongoCollection<Document> collection = getDocumentCollection();

        // Build regex pattern for multiple applications
        String pattern = applications.stream()
            .map(Pattern::quote)
            .collect(Collectors.joining("|", "^(", "):"));

        List<Document> pipeline = List.of(
            new Document("$match", new Document("code", new Document("$regex", pattern))),
            new Document("$project", new Document("subdomain",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 1)))),
            new Document("$group", new Document("_id", "$subdomain")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            String subdomain = doc.getString("_id");
            if (subdomain != null && !subdomain.isEmpty()) {
                results.add(subdomain);
            }
        }
        return results;
    }

    /**
     * Get distinct aggregates for a given application and subdomain.
     */
    public List<String> findDistinctAggregates(String application, String subdomain) {
        MongoCollection<Document> collection = getDocumentCollection();
        String prefix = application + ":" + subdomain + ":";

        List<Document> pipeline = List.of(
            new Document("$match", new Document("code",
                new Document("$regex", "^" + Pattern.quote(prefix)))),
            new Document("$project", new Document("aggregate",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 2)))),
            new Document("$group", new Document("_id", "$aggregate")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            String aggregate = doc.getString("_id");
            if (aggregate != null && !aggregate.isEmpty()) {
                results.add(aggregate);
            }
        }
        return results;
    }

    /**
     * Get all distinct aggregates across all event types (third segment of code).
     */
    public List<String> findAllDistinctAggregates() {
        MongoCollection<Document> collection = getDocumentCollection();

        List<Document> pipeline = List.of(
            new Document("$project", new Document("aggregate",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 2)))),
            new Document("$group", new Document("_id", "$aggregate")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            String aggregate = doc.getString("_id");
            if (aggregate != null && !aggregate.isEmpty()) {
                results.add(aggregate);
            }
        }
        return results;
    }

    /**
     * Get distinct aggregates optionally filtered by applications and subdomains.
     */
    public List<String> findDistinctAggregates(List<String> applications, List<String> subdomains) {
        if ((applications == null || applications.isEmpty()) &&
            (subdomains == null || subdomains.isEmpty())) {
            return findAllDistinctAggregates();
        }

        MongoCollection<Document> collection = getDocumentCollection();
        List<Document> pipeline = new ArrayList<>();

        // Build match conditions
        List<Document> matchConditions = new ArrayList<>();

        if (applications != null && !applications.isEmpty()) {
            matchConditions.add(new Document("$expr",
                new Document("$in", List.of(
                    new Document("$arrayElemAt", List.of(
                        new Document("$split", List.of("$code", ":")), 0)),
                    applications))));
        }

        if (subdomains != null && !subdomains.isEmpty()) {
            matchConditions.add(new Document("$expr",
                new Document("$in", List.of(
                    new Document("$arrayElemAt", List.of(
                        new Document("$split", List.of("$code", ":")), 1)),
                    subdomains))));
        }

        if (!matchConditions.isEmpty()) {
            pipeline.add(new Document("$match",
                matchConditions.size() == 1 ? matchConditions.get(0) :
                    new Document("$and", matchConditions)));
        }

        pipeline.add(new Document("$project", new Document("aggregate",
            new Document("$arrayElemAt", List.of(
                new Document("$split", List.of("$code", ":")), 2)))));
        pipeline.add(new Document("$group", new Document("_id", "$aggregate")));
        pipeline.add(new Document("$sort", new Document("_id", 1)));

        List<String> results = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            String aggregate = doc.getString("_id");
            if (aggregate != null && !aggregate.isEmpty()) {
                results.add(aggregate);
            }
        }
        return results;
    }

    /**
     * Find event types with multi-value filtering.
     * All filters are optional and work independently.
     */
    public List<EventType> findWithFilters(
            List<String> applications,
            List<String> subdomains,
            List<String> aggregates,
            EventTypeStatus status
    ) {
        // If no filters, use simple query
        if ((applications == null || applications.isEmpty()) &&
            (subdomains == null || subdomains.isEmpty()) &&
            (aggregates == null || aggregates.isEmpty()) &&
            status == null) {
            return findAllOrdered();
        }

        MongoCollection<Document> collection = getDocumentCollection();
        List<Document> matchConditions = new ArrayList<>();

        // Add status filter
        if (status != null) {
            matchConditions.add(new Document("status", status.name()));
        }

        // Add application filter (first segment)
        if (applications != null && !applications.isEmpty()) {
            matchConditions.add(new Document("$expr",
                new Document("$in", List.of(
                    new Document("$arrayElemAt", List.of(
                        new Document("$split", List.of("$code", ":")), 0)),
                    applications))));
        }

        // Add subdomain filter (second segment)
        if (subdomains != null && !subdomains.isEmpty()) {
            matchConditions.add(new Document("$expr",
                new Document("$in", List.of(
                    new Document("$arrayElemAt", List.of(
                        new Document("$split", List.of("$code", ":")), 1)),
                    subdomains))));
        }

        // Add aggregate filter (third segment)
        if (aggregates != null && !aggregates.isEmpty()) {
            matchConditions.add(new Document("$expr",
                new Document("$in", List.of(
                    new Document("$arrayElemAt", List.of(
                        new Document("$split", List.of("$code", ":")), 2)),
                    aggregates))));
        }

        List<Document> pipeline = List.of(
            new Document("$match", matchConditions.size() == 1 ? matchConditions.get(0) :
                new Document("$and", matchConditions)),
            new Document("$sort", new Document("code", 1))
        );

        // Execute aggregation and convert back to EventType entities
        List<EventType> results = new ArrayList<>();
        for (Document doc : collection.aggregate(pipeline)) {
            String id = doc.getString("_id");
            if (id != null) {
                EventType eventType = findById(id);
                if (eventType != null) {
                    results.add(eventType);
                }
            }
        }
        return results;
    }
}
