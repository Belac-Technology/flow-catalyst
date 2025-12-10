package tech.flowcatalyst.event;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Repository for Event documents in MongoDB using Panache.
 *
 * Indexes are created by MongoIndexInitializer on startup.
 */
@ApplicationScoped
public class EventRepository implements PanacheMongoRepositoryBase<Event, String> {

    /**
     * Find an event by its ID.
     *
     * @param id The event ID
     * @return The event if found
     */
    public Optional<Event> findByIdOptional(String id) {
        return Optional.ofNullable(findById(id));
    }

    /**
     * Find an event by deduplication ID.
     *
     * @param deduplicationId The deduplication ID
     * @return The event if found
     */
    public Optional<Event> findByDeduplicationId(String deduplicationId) {
        if (deduplicationId == null) {
            return Optional.empty();
        }
        return find("deduplicationId", deduplicationId).firstResultOptional();
    }

    /**
     * Check if an event with the given deduplication ID already exists.
     *
     * @param deduplicationId The deduplication ID to check
     * @return true if an event with this deduplication ID exists
     */
    public boolean existsByDeduplicationId(String deduplicationId) {
        if (deduplicationId == null) {
            return false;
        }
        return count("deduplicationId", deduplicationId) > 0;
    }

    /**
     * Insert a new event into the collection.
     *
     * @param event The event to insert
     */
    public void insert(Event event) {
        persist(event);
    }
}
