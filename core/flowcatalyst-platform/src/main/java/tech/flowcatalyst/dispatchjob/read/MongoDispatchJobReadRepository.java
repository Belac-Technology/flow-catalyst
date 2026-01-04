package tech.flowcatalyst.dispatchjob.read;

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
 * MongoDB implementation of DispatchJobReadRepository.
 * Package-private to prevent direct injection - use DispatchJobReadRepository interface.
 *
 * This collection is optimized for read operations with rich indexes.
 */
@ApplicationScoped
@Typed(DispatchJobReadRepository.class)
class MongoDispatchJobReadRepository implements DispatchJobReadRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<DispatchJobRead> collection() {
        return mongoClient.getDatabase(database).getCollection("dispatch_jobs_read", DispatchJobRead.class);
    }

    private MongoCollection<Document> documentCollection() {
        return mongoClient.getDatabase(database).getCollection("dispatch_jobs_read");
    }

    @Override
    public DispatchJobRead findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<DispatchJobRead> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public List<DispatchJobRead> findWithFilter(DispatchJobReadFilter filter) {
        Bson query = buildFilter(filter);

        return collection().find(query)
            .sort(Sorts.descending("createdAt"))
            .skip(filter.page() * filter.size())
            .limit(filter.size())
            .into(new ArrayList<>());
    }

    @Override
    public long countWithFilter(DispatchJobReadFilter filter) {
        Bson query = buildFilter(filter);
        return collection().countDocuments(query);
    }

    private Bson buildFilter(DispatchJobReadFilter filter) {
        List<Bson> conditions = new ArrayList<>();

        if (filter.clientIds() != null && !filter.clientIds().isEmpty()) {
            conditions.add(buildClientIdFilter(filter.clientIds()));
        }

        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            conditions.add(in("status", filter.statuses()));
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

        if (filter.codes() != null && !filter.codes().isEmpty()) {
            conditions.add(in("code", filter.codes()));
        }

        if (filter.source() != null && !filter.source().isBlank()) {
            conditions.add(eq("source", filter.source()));
        }

        if (filter.kind() != null && !filter.kind().isBlank()) {
            conditions.add(eq("kind", filter.kind()));
        }

        if (filter.subscriptionId() != null && !filter.subscriptionId().isBlank()) {
            conditions.add(eq("subscriptionId", filter.subscriptionId()));
        }

        if (filter.dispatchPoolId() != null && !filter.dispatchPoolId().isBlank()) {
            conditions.add(eq("dispatchPoolId", filter.dispatchPoolId()));
        }

        if (filter.messageGroup() != null && !filter.messageGroup().isBlank()) {
            conditions.add(eq("messageGroup", filter.messageGroup()));
        }

        if (filter.createdAfter() != null) {
            conditions.add(gte("createdAt", filter.createdAfter()));
        }

        if (filter.createdBefore() != null) {
            conditions.add(lte("createdAt", filter.createdBefore()));
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
                new Document("code", new Document("$regex", "^(" + String.join("|", request.applications()) + "):"))
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

        List<String> codes = getDistinctValues(collection, "code", aggregateMatch);

        List<String> statuses = getDistinctValues(collection, "status", new Document());

        return new FilterOptions(clients, applications, subdomains, aggregates, codes, statuses);
    }

    private List<String> getCombinedSegmentValues(MongoCollection<Document> collection, Document match,
                                                   String fieldName, int segmentIndex) {
        Set<String> values = new HashSet<>();

        collection.distinct(fieldName, match, String.class).into(values);

        List<String> codes = new ArrayList<>();
        collection.distinct("code", match, String.class).into(codes);
        for (String code : codes) {
            if (code != null) {
                String[] parts = code.split(":", 4);
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
    public List<DispatchJobRead> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public void persist(DispatchJobRead job) {
        collection().insertOne(job);
    }

    @Override
    public void update(DispatchJobRead job) {
        collection().replaceOne(eq("_id", job.id), job);
    }

    @Override
    public void delete(DispatchJobRead job) {
        collection().deleteOne(eq("_id", job.id));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
