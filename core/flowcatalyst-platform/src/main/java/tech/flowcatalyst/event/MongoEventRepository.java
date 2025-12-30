package tech.flowcatalyst.event;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import tech.flowcatalyst.platform.shared.Instrumented;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of EventRepository.
 * Package-private to prevent direct injection - use EventRepository interface.
 *
 * Indexes are created by MongoIndexInitializer on startup.
 */
@ApplicationScoped
@Typed(EventRepository.class)
@Instrumented(collection = "events")
class MongoEventRepository implements PanacheMongoRepositoryBase<Event, String>, EventRepository {

    @Override
    public Optional<Event> findByIdOptional(String id) {
        return Optional.ofNullable(findById(id));
    }

    @Override
    public Optional<Event> findByDeduplicationId(String deduplicationId) {
        if (deduplicationId == null) {
            return Optional.empty();
        }
        return find("deduplicationId", deduplicationId).firstResultOptional();
    }

    @Override
    public boolean existsByDeduplicationId(String deduplicationId) {
        if (deduplicationId == null) {
            return false;
        }
        return count("deduplicationId", deduplicationId) > 0;
    }

    @Override
    public void insert(Event event) {
        persist(event);
    }

    @Override
    public List<Event> findRecentPaged(int page, int size) {
        return findAll(io.quarkus.panache.common.Sort.by("time").descending())
            .page(page, size)
            .list();
    }

    // Delegate to Panache methods via interface
    @Override
    public Event findById(String id) {
        return PanacheMongoRepositoryBase.super.findById(id);
    }

    @Override
    public List<Event> listAll() {
        return PanacheMongoRepositoryBase.super.listAll();
    }

    @Override
    public long count() {
        return PanacheMongoRepositoryBase.super.count();
    }

    @Override
    public void persist(Event event) {
        PanacheMongoRepositoryBase.super.persist(event);
    }

    @Override
    public void persistAll(List<Event> events) {
        PanacheMongoRepositoryBase.super.persist(events);
    }

    @Override
    public void update(Event event) {
        PanacheMongoRepositoryBase.super.update(event);
    }

    @Override
    public void delete(Event event) {
        PanacheMongoRepositoryBase.super.delete(event);
    }

    @Override
    public boolean deleteById(String id) {
        return PanacheMongoRepositoryBase.super.deleteById(id);
    }
}
