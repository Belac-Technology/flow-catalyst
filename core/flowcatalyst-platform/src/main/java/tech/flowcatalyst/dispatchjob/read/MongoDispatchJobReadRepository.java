package tech.flowcatalyst.dispatchjob.read;

import com.mongodb.client.MongoCollection;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import org.bson.Document;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MongoDB implementation of DispatchJobReadRepository.
 * Package-private to prevent direct injection - use DispatchJobReadRepository interface.
 *
 * This collection is optimized for read operations with rich indexes.
 */
@ApplicationScoped
@Typed(DispatchJobReadRepository.class)
@Instrumented(collection = "dispatch_jobs_read")
class MongoDispatchJobReadRepository implements PanacheMongoRepositoryBase<DispatchJobRead, String>, DispatchJobReadRepository {

    @Override
    public Optional<DispatchJobRead> findByIdOptional(String id) {
        return Optional.ofNullable(findById(id));
    }

    @Override
    public List<DispatchJobRead> findWithFilter(DispatchJobReadFilter filter) {
        StringBuilder query = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        buildQuery(filter, query, params);

        String queryStr = query.isEmpty() ? "{}" : query.toString();
        Sort sort = Sort.by("createdAt").descending();

        return find(queryStr, sort, params)
            .page(filter.page(), filter.size())
            .list();
    }

    @Override
    public long countWithFilter(DispatchJobReadFilter filter) {
        StringBuilder query = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        buildQuery(filter, query, params);

        String queryStr = query.isEmpty() ? "{}" : query.toString();
        return count(queryStr, params);
    }

    private void buildQuery(DispatchJobReadFilter filter, StringBuilder query, Map<String, Object> params) {
        boolean first = true;

        if (filter.clientIds() != null && !filter.clientIds().isEmpty()) {
            first = appendClientIdFilter(filter.clientIds(), query, params, first);
        }

        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            if (!first) query.append(" and ");
            query.append("status in :statuses");
            params.put("statuses", filter.statuses());
            first = false;
        }

        if (filter.applications() != null && !filter.applications().isEmpty()) {
            if (!first) query.append(" and ");
            query.append("application in :applications");
            params.put("applications", filter.applications());
            first = false;
        }

        if (filter.subdomains() != null && !filter.subdomains().isEmpty()) {
            if (!first) query.append(" and ");
            query.append("subdomain in :subdomains");
            params.put("subdomains", filter.subdomains());
            first = false;
        }

        if (filter.aggregates() != null && !filter.aggregates().isEmpty()) {
            if (!first) query.append(" and ");
            query.append("aggregate in :aggregates");
            params.put("aggregates", filter.aggregates());
            first = false;
        }

        if (filter.codes() != null && !filter.codes().isEmpty()) {
            if (!first) query.append(" and ");
            query.append("code in :codes");
            params.put("codes", filter.codes());
            first = false;
        }

        if (filter.source() != null && !filter.source().isBlank()) {
            if (!first) query.append(" and ");
            query.append("source = :source");
            params.put("source", filter.source());
            first = false;
        }

        if (filter.kind() != null && !filter.kind().isBlank()) {
            if (!first) query.append(" and ");
            query.append("kind = :kind");
            params.put("kind", filter.kind());
            first = false;
        }

        if (filter.subscriptionId() != null && !filter.subscriptionId().isBlank()) {
            if (!first) query.append(" and ");
            query.append("subscriptionId = :subscriptionId");
            params.put("subscriptionId", filter.subscriptionId());
            first = false;
        }

        if (filter.dispatchPoolId() != null && !filter.dispatchPoolId().isBlank()) {
            if (!first) query.append(" and ");
            query.append("dispatchPoolId = :dispatchPoolId");
            params.put("dispatchPoolId", filter.dispatchPoolId());
            first = false;
        }

        if (filter.messageGroup() != null && !filter.messageGroup().isBlank()) {
            if (!first) query.append(" and ");
            query.append("messageGroup = :messageGroup");
            params.put("messageGroup", filter.messageGroup());
            first = false;
        }

        if (filter.createdAfter() != null) {
            if (!first) query.append(" and ");
            query.append("createdAt >= :createdAfter");
            params.put("createdAfter", filter.createdAfter());
            first = false;
        }

        if (filter.createdBefore() != null) {
            if (!first) query.append(" and ");
            query.append("createdAt <= :createdBefore");
            params.put("createdBefore", filter.createdBefore());
        }
    }

    private boolean appendClientIdFilter(List<String> clientIds, StringBuilder query, Map<String, Object> params, boolean first) {
        boolean hasNull = clientIds.stream().anyMatch(id -> "null".equalsIgnoreCase(id));
        List<String> nonNullIds = clientIds.stream()
            .filter(id -> !"null".equalsIgnoreCase(id))
            .toList();

        if (!first) query.append(" and ");

        if (hasNull && nonNullIds.isEmpty()) {
            query.append("clientId = null");
        } else if (hasNull) {
            query.append("(clientId = null or clientId in :clientIds)");
            params.put("clientIds", nonNullIds);
        } else {
            query.append("clientId in :clientIds");
            params.put("clientIds", nonNullIds);
        }

        return false;
    }

    @Override
    public FilterOptions getFilterOptions(FilterOptionsRequest request) {
        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = (MongoCollection<Document>) mongoCollection().withDocumentClass(Document.class);

        List<String> clients = getDistinctClientIds(collection);

        Document clientMatch = new Document();
        if (request.clientIds() != null && !request.clientIds().isEmpty()) {
            clientMatch = buildClientIdMatch(request.clientIds());
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

    private Document buildClientIdMatch(List<String> clientIds) {
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

    // Delegate to Panache methods via interface
    @Override
    public DispatchJobRead findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public List<DispatchJobRead> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(DispatchJobRead job) {
        PanacheMongoRepositoryBase.super.persist(job);
    }

    @Override
    public void update(DispatchJobRead job) {
        PanacheMongoRepositoryBase.super.update(job);
    }

    @Override
    public void delete(DispatchJobRead job) {
        PanacheMongoRepositoryBase.super.delete(job);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
