package tech.flowcatalyst.platform.eventtype;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository for EventType entities.
 * EventTypes are global (not tenant-scoped).
 */
@ApplicationScoped
public class EventTypeRepository implements PanacheRepository<EventType> {

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
        return list("code LIKE ?1 ORDER BY code", prefix + "%");
    }

    /**
     * Get distinct application names (first segment of code).
     */
    public List<String> findDistinctApplications() {
        return getEntityManager()
            .createQuery(
                "SELECT DISTINCT SUBSTRING(e.code, 1, LOCATE(':', e.code) - 1) FROM EventType e ORDER BY 1",
                String.class
            )
            .getResultList();
    }

    /**
     * Get distinct subdomains for a given application.
     */
    public List<String> findDistinctSubdomains(String application) {
        String prefix = application + ":";
        return getEntityManager()
            .createQuery(
                "SELECT DISTINCT SUBSTRING(e.code, :prefixLen + 1, " +
                "LOCATE(':', e.code, :prefixLen + 1) - :prefixLen - 1) " +
                "FROM EventType e WHERE e.code LIKE :pattern ORDER BY 1",
                String.class
            )
            .setParameter("prefixLen", prefix.length())
            .setParameter("pattern", prefix + "%")
            .getResultList();
    }

    /**
     * Get all distinct subdomains across all applications (second segment of code).
     * Uses native PostgreSQL query with split_part function.
     */
    @SuppressWarnings("unchecked")
    public List<String> findAllDistinctSubdomains() {
        return getEntityManager()
            .createNativeQuery(
                "SELECT DISTINCT split_part(code, ':', 2) AS subdomain " +
                "FROM event_types ORDER BY subdomain"
            )
            .getResultList();
    }

    /**
     * Get distinct subdomains optionally filtered by applications.
     */
    @SuppressWarnings("unchecked")
    public List<String> findDistinctSubdomains(List<String> applications) {
        if (applications == null || applications.isEmpty()) {
            return findAllDistinctSubdomains();
        }
        if (applications.size() == 1) {
            return findDistinctSubdomains(applications.get(0));
        }

        // Build IN clause for applications
        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT split_part(code, ':', 2) AS subdomain " +
            "FROM event_types WHERE split_part(code, ':', 1) IN ("
        );
        for (int i = 0; i < applications.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("?").append(i + 1);
        }
        sql.append(") ORDER BY subdomain");

        var query = getEntityManager().createNativeQuery(sql.toString());
        for (int i = 0; i < applications.size(); i++) {
            query.setParameter(i + 1, applications.get(i));
        }

        return query.getResultList();
    }

    /**
     * Get distinct aggregates for a given application and subdomain.
     */
    public List<String> findDistinctAggregates(String application, String subdomain) {
        String prefix = application + ":" + subdomain + ":";
        return getEntityManager()
            .createQuery(
                "SELECT DISTINCT SUBSTRING(e.code, :prefixLen + 1, " +
                "LOCATE(':', e.code, :prefixLen + 1) - :prefixLen - 1) " +
                "FROM EventType e WHERE e.code LIKE :pattern ORDER BY 1",
                String.class
            )
            .setParameter("prefixLen", prefix.length())
            .setParameter("pattern", prefix + "%")
            .getResultList();
    }

    /**
     * Get all distinct aggregates across all event types (third segment of code).
     * Uses native PostgreSQL query with split_part function.
     */
    @SuppressWarnings("unchecked")
    public List<String> findAllDistinctAggregates() {
        return getEntityManager()
            .createNativeQuery(
                "SELECT DISTINCT split_part(code, ':', 3) AS aggregate " +
                "FROM event_types ORDER BY aggregate"
            )
            .getResultList();
    }

    /**
     * Get distinct aggregates optionally filtered by applications and subdomains.
     * Uses native PostgreSQL query with split_part function.
     */
    @SuppressWarnings("unchecked")
    public List<String> findDistinctAggregates(List<String> applications, List<String> subdomains) {
        if ((applications == null || applications.isEmpty()) &&
            (subdomains == null || subdomains.isEmpty())) {
            return findAllDistinctAggregates();
        }

        StringBuilder sql = new StringBuilder(
            "SELECT DISTINCT split_part(code, ':', 3) AS aggregate FROM event_types WHERE 1=1"
        );

        int paramIndex = 1;

        if (applications != null && !applications.isEmpty()) {
            sql.append(" AND split_part(code, ':', 1) IN (");
            for (int i = 0; i < applications.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("?").append(paramIndex++);
            }
            sql.append(")");
        }

        if (subdomains != null && !subdomains.isEmpty()) {
            sql.append(" AND split_part(code, ':', 2) IN (");
            for (int i = 0; i < subdomains.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("?").append(paramIndex++);
            }
            sql.append(")");
        }

        sql.append(" ORDER BY aggregate");

        var query = getEntityManager().createNativeQuery(sql.toString());

        paramIndex = 1;
        if (applications != null) {
            for (String app : applications) {
                query.setParameter(paramIndex++, app);
            }
        }
        if (subdomains != null) {
            for (String sub : subdomains) {
                query.setParameter(paramIndex++, sub);
            }
        }

        return query.getResultList();
    }

    /**
     * Find event types with multi-value filtering.
     * All filters are optional and work independently.
     * Uses native PostgreSQL query with split_part function.
     */
    @SuppressWarnings("unchecked")
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

        StringBuilder sql = new StringBuilder("SELECT * FROM event_types WHERE 1=1");
        int paramIndex = 1;

        if (applications != null && !applications.isEmpty()) {
            sql.append(" AND split_part(code, ':', 1) IN (");
            for (int i = 0; i < applications.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("?").append(paramIndex++);
            }
            sql.append(")");
        }

        if (subdomains != null && !subdomains.isEmpty()) {
            sql.append(" AND split_part(code, ':', 2) IN (");
            for (int i = 0; i < subdomains.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("?").append(paramIndex++);
            }
            sql.append(")");
        }

        if (aggregates != null && !aggregates.isEmpty()) {
            sql.append(" AND split_part(code, ':', 3) IN (");
            for (int i = 0; i < aggregates.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("?").append(paramIndex++);
            }
            sql.append(")");
        }

        if (status != null) {
            sql.append(" AND status = ?").append(paramIndex++);
        }

        sql.append(" ORDER BY code");

        var query = getEntityManager().createNativeQuery(sql.toString(), EventType.class);

        paramIndex = 1;
        if (applications != null) {
            for (String app : applications) {
                query.setParameter(paramIndex++, app);
            }
        }
        if (subdomains != null) {
            for (String sub : subdomains) {
                query.setParameter(paramIndex++, sub);
            }
        }
        if (aggregates != null) {
            for (String agg : aggregates) {
                query.setParameter(paramIndex++, agg);
            }
        }
        if (status != null) {
            query.setParameter(paramIndex++, status.name());
        }

        return query.getResultList();
    }
}
