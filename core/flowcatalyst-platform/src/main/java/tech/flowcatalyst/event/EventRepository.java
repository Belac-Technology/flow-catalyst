package tech.flowcatalyst.event;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Event entities.
 * Exposes only approved data access methods - Panache internals are hidden.
 *
 * Indexes are created by MongoIndexInitializer on startup.
 */
public interface EventRepository {

    // Read operations
    Event findById(String id);
    Optional<Event> findByIdOptional(String id);
    Optional<Event> findByDeduplicationId(String deduplicationId);
    List<Event> listAll();
    List<Event> findRecentPaged(int page, int size);
    long count();
    boolean existsByDeduplicationId(String deduplicationId);

    // Write operations
    void insert(Event event);
    void persist(Event event);
    void persistAll(List<Event> events);
    void update(Event event);
    void delete(Event event);
    boolean deleteById(String id);
}
