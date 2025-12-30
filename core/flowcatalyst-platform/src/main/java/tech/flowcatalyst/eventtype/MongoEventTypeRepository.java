package tech.flowcatalyst.eventtype;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MongoDB implementation of EventTypeRepository.
 * Package-private to prevent direct injection - use EventTypeRepository interface.
 *
 * Event type codes follow the format: application:subdomain:aggregate:action
 */
@ApplicationScoped
@Typed(EventTypeRepository.class)
@Instrumented(collection = "event_types")
class MongoEventTypeRepository implements PanacheMongoRepositoryBase<EventType, String>, EventTypeRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> getDocumentCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("event_types");
    }

    @Override
    public Optional<EventType> findByCode(String code) {
        return find("code", code).firstResultOptional();
    }

    @Override
    public boolean existsByCode(String code) {
        return count("code", code) > 0;
    }

    @Override
    public List<EventType> findAllOrdered() {
        return listAll(Sort.by("code"));
    }

    @Override
    public List<EventType> findCurrent() {
        return list("status", Sort.by("code"), EventTypeStatus.CURRENT);
    }

    @Override
    public List<EventType> findArchived() {
        return list("status", Sort.by("code"), EventTypeStatus.ARCHIVE);
    }

    @Override
    public List<EventType> findByCodePrefix(String prefix) {
        return list("code like ?1", prefix + "%");
    }

    @Override
    public List<String> findDistinctApplications() {
        MongoCollection<Document> collection = getDocumentCollection();

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

    @Override
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

    @Override
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

    @Override
    public List<String> findDistinctSubdomains(List<String> applications) {
        if (applications == null || applications.isEmpty()) {
            return findAllDistinctSubdomains();
        }
        if (applications.size() == 1) {
            return findDistinctSubdomains(applications.get(0));
        }

        MongoCollection<Document> collection = getDocumentCollection();

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

    @Override
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

    @Override
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

    @Override
    public List<String> findDistinctAggregates(List<String> applications, List<String> subdomains) {
        if ((applications == null || applications.isEmpty()) &&
            (subdomains == null || subdomains.isEmpty())) {
            return findAllDistinctAggregates();
        }

        MongoCollection<Document> collection = getDocumentCollection();
        List<Document> pipeline = new ArrayList<>();

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

    @Override
    public List<EventType> findWithFilters(
            List<String> applications,
            List<String> subdomains,
            List<String> aggregates,
            EventTypeStatus status
    ) {
        if ((applications == null || applications.isEmpty()) &&
            (subdomains == null || subdomains.isEmpty()) &&
            (aggregates == null || aggregates.isEmpty()) &&
            status == null) {
            return findAllOrdered();
        }

        MongoCollection<Document> collection = getDocumentCollection();
        List<Document> matchConditions = new ArrayList<>();

        if (status != null) {
            matchConditions.add(new Document("status", status.name()));
        }

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

    // Delegate to Panache methods via interface
    @Override
    public EventType findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public Optional<EventType> findByIdOptional(String id) {
        return PanacheMongoRepositoryBase.super.findByIdOptional(id);
    }

    @Override
    public List<EventType> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(EventType eventType) {
        PanacheMongoRepositoryBase.super.persist(eventType);
    }

    @Override
    public void update(EventType eventType) {
        PanacheMongoRepositoryBase.super.update(eventType);
    }

    @Override
    public void delete(EventType eventType) {
        PanacheMongoRepositoryBase.super.delete(eventType);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
