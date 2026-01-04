package tech.flowcatalyst.event.read;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.mongodb.client.model.Filters.*;

/**
 * MongoDB implementation of EventReadRepository.
 * Package-private to prevent direct injection - use EventReadRepository interface.
 *
 * This collection is optimized for read operations with rich indexes.
 */
@ApplicationScoped
@Typed(EventReadRepository.class)
class MongoEventReadRepository implements EventReadRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<EventRead> collection() {
        return mongoClient.getDatabase(database).getCollection("events_read", EventRead.class);
    }

    private MongoCollection<Document> documentCollection() {
        return mongoClient.getDatabase(database).getCollection("events_read");
    }

    @Override
    public EventRead findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<EventRead> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public List<EventRead> findWithFilter(EventFilter filter) {
        Bson query = buildFilter(filter);

        return collection().find(query)
            .sort(Sorts.descending("time"))
            .skip(filter.page() * filter.size())
            .limit(filter.size())
            .into(new ArrayList<>());
    }

    @Override
    public long countWithFilter(EventFilter filter) {
        Bson query = buildFilter(filter);
        return collection().countDocuments(query);
    }

    private Bson buildFilter(EventFilter filter) {
        List<Bson> conditions = new ArrayList<>();

        if (filter.clientIds() != null && !filter.clientIds().isEmpty()) {
            conditions.add(buildClientIdFilter(filter.clientIds()));
        }

        if (filter.applications() != null && !filter.applications().isEmpty()) {
            conditions.add(in("application", filter.applications()));
        }

        if (filter.subdomains() != null && !filter.subdomains().isEmpty()) {
            conditions.add(in("subdomain", filter.subdomains()));
        }

        if (filter.aggregates() != null && !filter.aggregates().isEmpty()) {
            conditions.add(in("aggregate", filter.aggregates()));
        }

        if (filter.types() != null && !filter.types().isEmpty()) {
            conditions.add(in("type", filter.types()));
        }

        if (filter.source() != null && !filter.source().isBlank()) {
            conditions.add(eq("source", filter.source()));
        }

        if (filter.subject() != null && !filter.subject().isBlank()) {
            conditions.add(eq("subject", filter.subject()));
        }

        if (filter.correlationId() != null && !filter.correlationId().isBlank()) {
            conditions.add(eq("correlationId", filter.correlationId()));
        }

        if (filter.messageGroup() != null && !filter.messageGroup().isBlank()) {
            conditions.add(eq("messageGroup", filter.messageGroup()));
        }

        if (filter.timeAfter() != null) {
            conditions.add(gte("time", filter.timeAfter()));
        }

        if (filter.timeBefore() != null) {
            conditions.add(lte("time", filter.timeBefore()));
        }

        if (conditions.isEmpty()) {
            return new Document();
        }

        return and(conditions);
    }

    private Bson buildClientIdFilter(List<String> clientIds) {
        boolean hasNull = clientIds.stream().anyMatch(id -> "null".equalsIgnoreCase(id));
        List<String> nonNullIds = clientIds.stream()
            .filter(id -> !"null".equalsIgnoreCase(id))
            .toList();

        if (hasNull && nonNullIds.isEmpty()) {
            return eq("clientId", null);
        } else if (hasNull) {
            return or(eq("clientId", null), in("clientId", nonNullIds));
        } else {
            return in("clientId", nonNullIds);
        }
    }

    @Override
    public FilterOptions getFilterOptions(FilterOptionsRequest request) {
        MongoCollection<Document> collection = documentCollection();

        List<String> clients = getDistinctClientIds(collection);

        Document clientMatch = new Document();
        if (request.clientIds() != null && !request.clientIds().isEmpty()) {
            clientMatch = buildClientIdMatchDocument(request.clientIds());
        }

        List<String> applications = getCombinedSegmentValues(collection, clientMatch, "application", 0);

        Document appMatch = new Document(clientMatch);
        if (request.applications() != null && !request.applications().isEmpty()) {
            appMatch.append("$or", List.of(
                new Document("application", new Document("$in", request.applications())),
                new Document("type", new Document("$regex", "^(" + String.join("|", request.applications()) + "):"))
            ));
        }

        List<String> subdomains = getCombinedSegmentValues(collection, appMatch, "subdomain", 1);

        Document subdomainMatch = new Document(appMatch);
        if (request.subdomains() != null && !request.subdomains().isEmpty()) {
            subdomainMatch.append("subdomain", new Document("$in", request.subdomains()));
        }

        List<String> aggregates = getCombinedSegmentValues(collection, subdomainMatch, "aggregate", 2);

        Document aggregateMatch = new Document(subdomainMatch);
        if (request.aggregates() != null && !request.aggregates().isEmpty()) {
            aggregateMatch.append("aggregate", new Document("$in", request.aggregates()));
        }

        List<String> types = getDistinctValues(collection, "type", aggregateMatch);

        return new FilterOptions(clients, applications, subdomains, aggregates, types);
    }

    private List<String> getCombinedSegmentValues(MongoCollection<Document> collection, Document match,
                                                   String fieldName, int segmentIndex) {
        Set<String> values = new HashSet<>();

        collection.distinct(fieldName, match, String.class).into(values);

        List<String> types = new ArrayList<>();
        collection.distinct("type", match, String.class).into(types);
        for (String type : types) {
            if (type != null) {
                String[] parts = type.split(":", 4);
                if (parts.length > segmentIndex) {
                    String segment = parts[segmentIndex];
                    if (segment != null && !segment.isBlank()) {
                        values.add(segment.trim());
                    }
                }
            }
        }

        values.remove(null);
        return values.stream()
            .filter(s -> !s.isBlank())
            .sorted(String::compareToIgnoreCase)
            .toList();
    }

    private Document buildClientIdMatchDocument(List<String> clientIds) {
        boolean hasNull = clientIds.stream().anyMatch(id -> "null".equalsIgnoreCase(id));
        List<String> nonNullIds = clientIds.stream()
            .filter(id -> !"null".equalsIgnoreCase(id))
            .toList();

        if (hasNull && nonNullIds.isEmpty()) {
            return new Document("clientId", null);
        } else if (hasNull) {
            return new Document("$or", List.of(
                new Document("clientId", null),
                new Document("clientId", new Document("$in", nonNullIds))
            ));
        } else {
            return new Document("clientId", new Document("$in", nonNullIds));
        }
    }

    private List<String> getDistinctValues(MongoCollection<Document> collection, String field, Document match) {
        List<String> values = new ArrayList<>();

        collection.distinct(field, match, String.class)
            .into(values);

        values.sort(String::compareToIgnoreCase);

        return values;
    }

    private List<String> getDistinctClientIds(MongoCollection<Document> collection) {
        List<String> values = new ArrayList<>();

        Document nullMatch = new Document("clientId", null);
        if (collection.countDocuments(nullMatch) > 0) {
            values.add(null);
        }

        collection.distinct("clientId", String.class)
            .into(values);

        values.sort((a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            return a.compareToIgnoreCase(b);
        });

        return values;
    }

    @Override
    public List<EventRead> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public void persist(EventRead event) {
        collection().insertOne(event);
    }

    @Override
    public void update(EventRead event) {
        collection().replaceOne(eq("_id", event.id), event);
    }

    @Override
    public void delete(EventRead event) {
        collection().deleteOne(eq("_id", event.id));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
