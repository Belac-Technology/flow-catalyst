package tech.flowcatalyst.eventtype;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.*;

/**
 * MongoDB implementation of EventTypeRepository.
 * Package-private to prevent direct injection - use EventTypeRepository interface.
 *
 * Event type codes follow the format: application:subdomain:aggregate:action
 */
@ApplicationScoped
@Typed(EventTypeRepository.class)
class MongoEventTypeRepository implements EventTypeRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<EventType> collection() {
        return mongoClient.getDatabase(databaseName).getCollection("event_types", EventType.class);
    }

    private MongoCollection<Document> getDocumentCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("event_types");
    }

    @Override
    public EventType findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<EventType> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<EventType> findByCode(String code) {
        return Optional.ofNullable(collection().find(eq("code", code)).first());
    }

    @Override
    public boolean existsByCode(String code) {
        return collection().countDocuments(eq("code", code)) > 0;
    }

    @Override
    public List<EventType> findAllOrdered() {
        return collection().find().sort(ascending("code")).into(new ArrayList<>());
    }

    @Override
    public List<EventType> findCurrent() {
        return collection().find(eq("status", EventTypeStatus.CURRENT.name()))
            .sort(ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<EventType> findArchived() {
        return collection().find(eq("status", EventTypeStatus.ARCHIVE.name()))
            .sort(ascending("code"))
            .into(new ArrayList<>());
    }

    @Override
    public List<EventType> findByCodePrefix(String prefix) {
        return collection().find(regex("code", "^" + Pattern.quote(prefix)))
            .into(new ArrayList<>());
    }

    @Override
    public List<EventType> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public List<String> findDistinctApplications() {
        MongoCollection<Document> coll = getDocumentCollection();

        List<Document> pipeline = List.of(
            new Document("$project", new Document("application",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 0)))),
            new Document("$group", new Document("_id", "$application")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : coll.aggregate(pipeline)) {
            String app = doc.getString("_id");
            if (app != null && !app.isEmpty()) {
                results.add(app);
            }
        }
        return results;
    }

    @Override
    public List<String> findDistinctSubdomains(String application) {
        MongoCollection<Document> coll = getDocumentCollection();
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
        for (Document doc : coll.aggregate(pipeline)) {
            String subdomain = doc.getString("_id");
            if (subdomain != null && !subdomain.isEmpty()) {
                results.add(subdomain);
            }
        }
        return results;
    }

    @Override
    public List<String> findAllDistinctSubdomains() {
        MongoCollection<Document> coll = getDocumentCollection();

        List<Document> pipeline = List.of(
            new Document("$project", new Document("subdomain",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 1)))),
            new Document("$group", new Document("_id", "$subdomain")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : coll.aggregate(pipeline)) {
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

        MongoCollection<Document> coll = getDocumentCollection();

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
        for (Document doc : coll.aggregate(pipeline)) {
            String subdomain = doc.getString("_id");
            if (subdomain != null && !subdomain.isEmpty()) {
                results.add(subdomain);
            }
        }
        return results;
    }

    @Override
    public List<String> findDistinctAggregates(String application, String subdomain) {
        MongoCollection<Document> coll = getDocumentCollection();
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
        for (Document doc : coll.aggregate(pipeline)) {
            String aggregate = doc.getString("_id");
            if (aggregate != null && !aggregate.isEmpty()) {
                results.add(aggregate);
            }
        }
        return results;
    }

    @Override
    public List<String> findAllDistinctAggregates() {
        MongoCollection<Document> coll = getDocumentCollection();

        List<Document> pipeline = List.of(
            new Document("$project", new Document("aggregate",
                new Document("$arrayElemAt", List.of(
                    new Document("$split", List.of("$code", ":")), 2)))),
            new Document("$group", new Document("_id", "$aggregate")),
            new Document("$sort", new Document("_id", 1))
        );

        List<String> results = new ArrayList<>();
        for (Document doc : coll.aggregate(pipeline)) {
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

        MongoCollection<Document> coll = getDocumentCollection();
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
        for (Document doc : coll.aggregate(pipeline)) {
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

        MongoCollection<Document> coll = getDocumentCollection();
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
        for (Document doc : coll.aggregate(pipeline)) {
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

    @Override
    public void persist(EventType eventType) {
        collection().insertOne(eventType);
    }

    @Override
    public void update(EventType eventType) {
        collection().replaceOne(eq("_id", eventType.id()), eventType);
    }

    @Override
    public void delete(EventType eventType) {
        collection().deleteOne(eq("_id", eventType.id()));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
