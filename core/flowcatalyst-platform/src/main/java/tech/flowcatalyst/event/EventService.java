package tech.flowcatalyst.event;

import com.mongodb.MongoWriteException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import tech.flowcatalyst.event.operations.CreateEvent;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.Optional;

/**
 * Service for Event operations.
 *
 * Handles event creation and validation. Events are stored in MongoDB
 * for high-volume write performance, with later projection to PostgreSQL
 * for efficient querying.
 */
@ApplicationScoped
public class EventService {

    @Inject
    EventRepository eventRepository;

    /**
     * Create a new event.
     *
     * @param operation The create event operation
     * @return The created event
     * @throws BadRequestException if validation fails or duplicate deduplication ID
     */
    public Event create(CreateEvent operation) {
        // Validate required fields
        validateCreateEvent(operation);

        // Check for duplicate deduplication ID
        if (operation.deduplicationId() != null) {
            Optional<Event> existing = eventRepository.findByDeduplicationId(operation.deduplicationId());
            if (existing.isPresent()) {
                // Return the existing event for idempotency
                return existing.get();
            }
        }

        // Create the event
        Event event = new Event(
            TsidGenerator.generate(),
            operation.specVersion(),
            operation.type(),
            operation.source(),
            operation.subject(),
            operation.time() != null ? operation.time() : Instant.now(),
            operation.data(),
            operation.correlationId(),
            operation.causationId(),
            operation.deduplicationId(),
            operation.messageGroup(),
            operation.contextData()
        );

        try {
            eventRepository.insert(event);
        } catch (MongoWriteException e) {
            // Handle duplicate key error for deduplicationId
            if (e.getCode() == 11000 && operation.deduplicationId() != null) {
                // Race condition - another request created the event
                return eventRepository.findByDeduplicationId(operation.deduplicationId())
                    .orElseThrow(() -> new RuntimeException("Unexpected state: duplicate key but event not found"));
            }
            throw e;
        }

        return event;
    }

    /**
     * Find an event by its ID.
     *
     * @param id The event ID
     * @return The event if found
     */
    public Optional<Event> findById(Long id) {
        return eventRepository.findByIdOptional(id);
    }

    /**
     * Find an event by deduplication ID.
     *
     * @param deduplicationId The deduplication ID
     * @return The event if found
     */
    public Optional<Event> findByDeduplicationId(String deduplicationId) {
        return eventRepository.findByDeduplicationId(deduplicationId);
    }

    /**
     * Validate the create event operation.
     */
    private void validateCreateEvent(CreateEvent operation) {
        if (operation.specVersion() == null || operation.specVersion().isBlank()) {
            throw new BadRequestException("specVersion is required");
        }
        if (operation.type() == null || operation.type().isBlank()) {
            throw new BadRequestException("type is required");
        }
        if (operation.source() == null || operation.source().isBlank()) {
            throw new BadRequestException("source is required");
        }
        if (operation.subject() == null || operation.subject().isBlank()) {
            throw new BadRequestException("subject is required");
        }
        if (operation.messageGroup() == null || operation.messageGroup().isBlank()) {
            throw new BadRequestException("messageGroup is required");
        }
    }
}
