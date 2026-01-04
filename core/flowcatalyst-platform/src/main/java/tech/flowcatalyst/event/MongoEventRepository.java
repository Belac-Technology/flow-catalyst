package tech.flowcatalyst.event;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Sorts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

/**
 * MongoDB implementation of EventRepository.
 * Package-private to prevent direct injection - use EventRepository interface.
 *
 * Indexes are created by MongoIndexInitializer on startup.
 */
@ApplicationScoped
@Typed(EventRepository.class)
class MongoEventRepository implements EventRepository {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    private MongoCollection<Event> collection() {
        return mongoClient.getDatabase(database).getCollection("events", Event.class);
    }

    @Override
    public Event findById(String id) {
        return collection().find(eq("_id", id)).first();
    }

    @Override
    public Optional<Event> findByIdOptional(String id) {
        return Optional.ofNullable(collection().find(eq("_id", id)).first());
    }

    @Override
    public Optional<Event> findByDeduplicationId(String deduplicationId) {
        if (deduplicationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(collection().find(eq("deduplicationId", deduplicationId)).first());
    }

    @Override
    public boolean existsByDeduplicationId(String deduplicationId) {
        if (deduplicationId == null) {
            return false;
        }
        return collection().countDocuments(eq("deduplicationId", deduplicationId)) > 0;
    }

    @Override
    public void insert(Event event) {
        collection().insertOne(event);
    }

    @Override
    public List<Event> findRecentPaged(int page, int size) {
        return collection().find()
            .sort(Sorts.descending("time"))
            .skip(page * size)
            .limit(size)
            .into(new ArrayList<>());
    }

    @Override
    public List<Event> listAll() {
        return collection().find().into(new ArrayList<>());
    }

    @Override
    public long count() {
        return collection().countDocuments();
    }

    @Override
    public void persist(Event event) {
        collection().insertOne(event);
    }

    @Override
    public void persistAll(List<Event> events) {
        if (events != null && !events.isEmpty()) {
            collection().insertMany(events);
        }
    }

    @Override
    public void update(Event event) {
        collection().replaceOne(eq("_id", event.id), event);
    }

    @Override
    public void delete(Event event) {
        collection().deleteOne(eq("_id", event.id));
    }

    @Override
    public boolean deleteById(String id) {
        return collection().deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }
}
