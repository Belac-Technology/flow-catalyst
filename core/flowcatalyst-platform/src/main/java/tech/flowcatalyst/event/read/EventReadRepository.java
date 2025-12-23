package tech.flowcatalyst.event.read;

import com.mongodb.client.MongoCollection;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for querying the events_read projection collection.
 *
 * This collection is optimized for read operations with rich indexes.
 */
@ApplicationScoped
public class EventReadRepository implements PanacheMongoRepositoryBase<EventRead, String> {

    public Optional<EventRead> findByIdOptional(String id) {
        return Optional.ofNullable(findById(id));
    }

    /**
     * Find events with optional filters and pagination.
     */
    public List<EventRead> findWithFilter(EventFilter filter) {
        StringBuilder query = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        buildQuery(filter, query, params);

        String queryStr = query.isEmpty() ? "{}" : query.toString();
        Sort sort = Sort.by("time").descending();

        return find(queryStr, sort, params)
            .page(filter.page(), filter.size())
            .list();
    }

    /**
     * Count events matching the filter.
     */
    public long countWithFilter(EventFilter filter) {
        StringBuilder query = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        buildQuery(filter, query, params);

        String queryStr = query.isEmpty() ? "{}" : query.toString();
        return count(queryStr, params);
    }

    private void buildQuery(EventFilter filter, StringBuilder query, Map<String, Object> params) {
        boolean first = true;

        // Multi-value filters (OR within each filter)
        if (filter.clientIds() != null && !filter.clientIds().isEmpty()) {
            first = appendClientIdFilter(filter.clientIds(), query, params, first);
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

        if (filter.types() != null && !filter.types().isEmpty()) {
            if (!first) query.append(" and ");
            query.append("type in :types");
            params.put("types", filter.types());
            first = false;
        }

        // Single-value filters
        if (filter.source() != null && !filter.source().isBlank()) {
            if (!first) query.append(" and ");
            query.append("source = :source");
            params.put("source", filter.source());
            first = false;
        }

        if (filter.subject() != null && !filter.subject().isBlank()) {
            if (!first) query.append(" and ");
            query.append("subject = :subject");
            params.put("subject", filter.subject());
            first = false;
        }

        if (filter.correlationId() != null && !filter.correlationId().isBlank()) {
            if (!first) query.append(" and ");
            query.append("correlationId = :correlationId");
            params.put("correlationId", filter.correlationId());
            first = false;
        }

        if (filter.messageGroup() != null && !filter.messageGroup().isBlank()) {
            if (!first) query.append(" and ");
            query.append("messageGroup = :messageGroup");
            params.put("messageGroup", filter.messageGroup());
            first = false;
        }

        if (filter.timeAfter() != null) {
            if (!first) query.append(" and ");
            query.append("time >= :timeAfter");
            params.put("timeAfter", filter.timeAfter());
            first = false;
        }

        if (filter.timeBefore() != null) {
            if (!first) query.append(" and ");
            query.append("time <= :timeBefore");
            params.put("timeBefore", filter.timeBefore());
        }
    }

    /**
     * Handle clientId filter with special support for null (platform events).
     * The string "null" in the list means filter for clientId = null.
     */
    private boolean appendClientIdFilter(List<String> clientIds, StringBuilder query, Map<String, Object> params, boolean first) {
        boolean hasNull = clientIds.stream().anyMatch(id -> "null".equalsIgnoreCase(id));
        List<String> nonNullIds = clientIds.stream()
            .filter(id -> !"null".equalsIgnoreCase(id))
            .toList();

        if (!first) query.append(" and ");

        if (hasNull && nonNullIds.isEmpty()) {
            // Only filtering for null clientId (platform events)
            query.append("clientId = null");
        } else if (hasNull) {
            // Mix of null and specific clientIds
            query.append("(clientId = null or clientId in :clientIds)");
            params.put("clientIds", nonNullIds);
        } else {
            // Only specific clientIds
            query.append("clientId in :clientIds");
            params.put("clientIds", nonNullIds);
        }

        return false;
    }

    // ========================================================================
    // Filter Options (for cascading filter UI)
    // ========================================================================

    /**
     * Get filter options for the cascading filter UI.
     *
     * <p>Returns distinct values for each filter level, narrowed by current selections.</p>
     *
     * @param currentFilter The current filter selections (used to narrow downstream options)
     * @return Filter options with available values for each level
     */
    public FilterOptions getFilterOptions(FilterOptionsRequest request) {
        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = (MongoCollection<Document>) mongoCollection().withDocumentClass(Document.class);

        // Get distinct clients (always unfiltered for top-level, includes null for platform events)
        List<String> clients = getDistinctClientIds(collection);

        // Build base match for clientId filter
        Document clientMatch = new Document();
        if (request.clientIds() != null && !request.clientIds().isEmpty()) {
            clientMatch = buildClientIdMatch(request.clientIds());
        }

        // Get applications: combine denormalized field + parsed from type (for legacy events)
        List<String> applications = getCombinedSegmentValues(collection, clientMatch, "application", 0);

        // Build match for application filter
        Document appMatch = new Document(clientMatch);
        if (request.applications() != null && !request.applications().isEmpty()) {
            appMatch.append("$or", List.of(
                new Document("application", new Document("$in", request.applications())),
                // Also match by type prefix for events without denormalized fields
                new Document("type", new Document("$regex", "^(" + String.join("|", request.applications()) + "):"))
            ));
        }

        // Get subdomains: combine denormalized field + parsed from type
        List<String> subdomains = getCombinedSegmentValues(collection, appMatch, "subdomain", 1);

        // Build match for subdomain filter
        Document subdomainMatch = new Document(appMatch);
        if (request.subdomains() != null && !request.subdomains().isEmpty()) {
            subdomainMatch.append("subdomain", new Document("$in", request.subdomains()));
        }

        // Get aggregates: combine denormalized field + parsed from type
        List<String> aggregates = getCombinedSegmentValues(collection, subdomainMatch, "aggregate", 2);

        // Build match for aggregate filter
        Document aggregateMatch = new Document(subdomainMatch);
        if (request.aggregates() != null && !request.aggregates().isEmpty()) {
            aggregateMatch.append("aggregate", new Document("$in", request.aggregates()));
        }

        // Get distinct event types
        List<String> types = getDistinctValues(collection, "type", aggregateMatch);

        return new FilterOptions(clients, applications, subdomains, aggregates, types);
    }

    /**
     * Get combined segment values from both denormalized field and parsed type.
     * This ensures we get values from both new events (with denormalized fields)
     * and legacy events (without denormalized fields).
     */
    private List<String> getCombinedSegmentValues(MongoCollection<Document> collection, Document match,
                                                   String fieldName, int segmentIndex) {
        java.util.Set<String> values = new java.util.HashSet<>();

        // Get from denormalized field
        collection.distinct(fieldName, match, String.class).into(values);

        // Also parse from type field for legacy events
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

        // Remove nulls and sort
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

    private Document buildMatchDocument(FilterOptionsRequest request) {
        Document match = new Document();

        if (request.clientIds() != null && !request.clientIds().isEmpty()) {
            Document clientMatch = buildClientIdMatch(request.clientIds());
            match.putAll(clientMatch);
        }
        if (request.applications() != null && !request.applications().isEmpty()) {
            match.append("application", new Document("$in", request.applications()));
        }
        if (request.subdomains() != null && !request.subdomains().isEmpty()) {
            match.append("subdomain", new Document("$in", request.subdomains()));
        }
        if (request.aggregates() != null && !request.aggregates().isEmpty()) {
            match.append("aggregate", new Document("$in", request.aggregates()));
        }

        return match;
    }

    /**
     * Get distinct non-null values for a field.
     */
    private List<String> getDistinctValues(MongoCollection<Document> collection, String field, Document match) {
        List<String> values = new ArrayList<>();

        // Use distinct with filter - note: this only returns non-null values
        collection.distinct(field, match, String.class)
            .into(values);

        // Sort alphabetically
        values.sort(String::compareToIgnoreCase);

        return values;
    }

    /**
     * Get distinct clientId values, including null (for platform events).
     */
    private List<String> getDistinctClientIds(MongoCollection<Document> collection) {
        List<String> values = new ArrayList<>();

        // Check if there are any documents with null clientId
        Document nullMatch = new Document("clientId", null);
        if (collection.countDocuments(nullMatch) > 0) {
            values.add(null);  // Add null to represent platform events
        }

        // Get non-null distinct values
        collection.distinct("clientId", String.class)
            .into(values);

        // Sort: null first, then alphabetically
        values.sort((a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            return a.compareToIgnoreCase(b);
        });

        return values;
    }

    /**
     * Request for filter options.
     */
    public record FilterOptionsRequest(
        List<String> clientIds,
        List<String> applications,
        List<String> subdomains,
        List<String> aggregates
    ) {}

    /**
     * Available options for each filter level.
     */
    public record FilterOptions(
        List<String> clients,       // All distinct clientIds (null = platform)
        List<String> applications,  // Narrowed by clientIds
        List<String> subdomains,    // Narrowed by clientIds + applications
        List<String> aggregates,    // Narrowed by clientIds + applications + subdomains
        List<String> types          // Narrowed by all above
    ) {}

    /**
     * Filter criteria for event queries.
     *
     * <p>Multi-value fields (applications, subdomains, etc.) use OR logic within each field.</p>
     * <p>The clientIds field supports a special value "null" to explicitly filter for events
     * with no client (platform/internal events).</p>
     */
    public record EventFilter(
        List<String> clientIds,       // Multi-select, "null" string = platform events
        List<String> applications,    // Multi-select
        List<String> subdomains,      // Multi-select
        List<String> aggregates,      // Multi-select
        List<String> types,           // Multi-select (full event type codes)
        String source,
        String subject,
        String correlationId,
        String messageGroup,
        Instant timeAfter,
        Instant timeBefore,
        Integer page,
        Integer size
    ) {
        public EventFilter {
            if (page == null || page < 0) {
                page = 0;
            }
            if (size == null || size < 1 || size > 100) {
                size = 20;
            }
        }

        /**
         * Builder for constructing EventFilter with defaults.
         */
        public static EventFilter of(
            List<String> clientIds,
            List<String> applications,
            List<String> subdomains,
            List<String> aggregates,
            List<String> types,
            String source,
            String subject,
            String correlationId,
            String messageGroup,
            Instant timeAfter,
            Instant timeBefore,
            Integer page,
            Integer size
        ) {
            return new EventFilter(
                clientIds, applications, subdomains, aggregates, types,
                source, subject, correlationId, messageGroup,
                timeAfter, timeBefore, page, size
            );
        }
    }
}
