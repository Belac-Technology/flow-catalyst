package tech.flowcatalyst.eventtype;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import tech.flowcatalyst.eventtype.operations.*;
import tech.flowcatalyst.platform.audit.AuditContext;
import tech.flowcatalyst.platform.audit.AuditLog;
import tech.flowcatalyst.platform.audit.AuditLogRepository;
import tech.flowcatalyst.platform.shared.TsidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Service for EventType operations with enforced audit logging.
 *
 * All operations must go through the execute() method which:
 * 1. Validates the audit context is set (principal ID required)
 * 2. Executes the operation
 * 3. Writes the audit log entry
 *
 * This ensures no operation can be performed without audit logging.
 */
@ApplicationScoped
public class EventTypeService {

    private static final String ENTITY_TYPE = "EventType";

    // Code format: {app}:{subdomain}:{aggregate}:{event}
    // Each segment: lowercase alphanumeric with hyphens, starting with letter
    private static final Pattern CODE_PATTERN = Pattern.compile(
        "^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$"
    );

    // Version format: MAJOR.MINOR (e.g., 1.0, 2.1)
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+$");

    @Inject
    EventTypeRepository repo;

    @Inject
    AuditLogRepository auditRepo;

    @Inject
    AuditContext auditContext;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Execute an EventType operation with audit logging.
     *
     * @param operation The operation to execute
     * @return The affected EventType (or null for delete)
     * @throws IllegalStateException if audit context is not set
     */
    public EventType execute(EventTypeOperation operation) {
        // 1. Validate principal is set - operations cannot proceed without audit context
        Long principalId = auditContext.requirePrincipalId();

        // 2. Execute the operation
        EventType result = switch (operation) {
            case CreateEventType op -> doCreate(op);
            case UpdateEventType op -> doUpdate(op);
            case AddSchema op -> doAddSchema(op);
            case FinaliseSchema op -> doFinalise(op);
            case DeprecateSchema op -> doDeprecate(op);
            case ArchiveEventType op -> doArchive(op);
            case DeleteEventType op -> doDelete(op);
        };

        // 3. Write audit log (always happens if we get here)
        Long entityId = result != null ? result.id : extractEntityId(operation);
        writeAuditLog(operation, entityId, principalId);

        return result;
    }

    // ========================================================================
    // Operation Implementations
    // ========================================================================

    private EventType doCreate(CreateEventType op) {
        // Validate code format
        if (!isValidCode(op.code())) {
            throw new BadRequestException(
                "Invalid code format. Must be {app}:{subdomain}:{aggregate}:{event} " +
                "with lowercase alphanumeric segments"
            );
        }

        // Check uniqueness (globally unique, not tenant-scoped)
        if (repo.existsByCode(op.code())) {
            throw new BadRequestException("Event type code already exists: " + op.code());
        }

        // Validate name
        if (op.name() == null || op.name().isBlank()) {
            throw new BadRequestException("Name is required");
        }
        if (op.name().length() > 100) {
            throw new BadRequestException("Name must be 100 characters or less");
        }

        // Validate description
        if (op.description() != null && op.description().length() > 255) {
            throw new BadRequestException("Description must be 255 characters or less");
        }

        EventType eventType = new EventType();
        eventType.id = TsidGenerator.generate();
        eventType.code = op.code().toLowerCase();
        eventType.name = op.name();
        eventType.description = op.description();
        eventType.status = EventTypeStatus.CURRENT;
        eventType.specVersions = new ArrayList<>();

        repo.persist(eventType);
        return eventType;
    }

    private EventType doUpdate(UpdateEventType op) {
        EventType eventType = findOrThrow(op.eventTypeId());

        if (op.name() != null) {
            if (op.name().isBlank()) {
                throw new BadRequestException("Name cannot be blank");
            }
            if (op.name().length() > 100) {
                throw new BadRequestException("Name must be 100 characters or less");
            }
            eventType.name = op.name();
        }

        if (op.description() != null) {
            if (op.description().length() > 255) {
                throw new BadRequestException("Description must be 255 characters or less");
            }
            eventType.description = op.description();
        }

        repo.update(eventType);
        return eventType;
    }

    private EventType doAddSchema(AddSchema op) {
        EventType eventType = findOrThrow(op.eventTypeId());

        // Cannot add schema to archived event type
        if (eventType.status == EventTypeStatus.ARCHIVE) {
            throw new BadRequestException("Cannot add schema to archived event type");
        }

        // Validate version format
        if (!isValidVersion(op.version())) {
            throw new BadRequestException("Invalid version format. Must be MAJOR.MINOR (e.g., 1.0, 2.1)");
        }

        // Check version doesn't already exist
        if (eventType.hasVersion(op.version())) {
            throw new BadRequestException("Version already exists: " + op.version());
        }

        // Validate required fields
        if (op.mimeType() == null || op.mimeType().isBlank()) {
            throw new BadRequestException("MIME type is required");
        }
        if (op.schema() == null || op.schema().isBlank()) {
            throw new BadRequestException("Schema is required");
        }
        if (op.schemaType() == null) {
            throw new BadRequestException("Schema type is required");
        }

        // Create new spec version in FINALISING status
        SpecVersion specVersion = new SpecVersion(
            op.version(),
            op.mimeType(),
            op.schema(),
            op.schemaType(),
            SpecVersionStatus.FINALISING
        );

        if (eventType.specVersions == null) {
            eventType.specVersions = new ArrayList<>();
        }
        eventType.specVersions.add(specVersion);

        repo.update(eventType);
        return eventType;
    }

    private EventType doFinalise(FinaliseSchema op) {
        EventType eventType = findOrThrow(op.eventTypeId());

        SpecVersion specVersion = eventType.findSpecVersion(op.version());
        if (specVersion == null) {
            throw new NotFoundException("Version not found: " + op.version());
        }

        if (specVersion.status() != SpecVersionStatus.FINALISING) {
            throw new BadRequestException("Can only finalise schemas in FINALISING status");
        }

        int majorVersion = specVersion.majorVersion();

        // Deprecate any existing CURRENT schema with same major version
        List<SpecVersion> updatedVersions = new ArrayList<>();
        for (SpecVersion sv : eventType.specVersions) {
            if (sv.version().equals(op.version())) {
                // This is the one we're finalising
                updatedVersions.add(sv.withStatus(SpecVersionStatus.CURRENT));
            } else if (sv.majorVersion() == majorVersion && sv.status() == SpecVersionStatus.CURRENT) {
                // Deprecate existing CURRENT with same major version
                updatedVersions.add(sv.withStatus(SpecVersionStatus.DEPRECATED));
            } else {
                updatedVersions.add(sv);
            }
        }

        eventType.specVersions = updatedVersions;
        repo.update(eventType);
        return eventType;
    }

    private EventType doDeprecate(DeprecateSchema op) {
        EventType eventType = findOrThrow(op.eventTypeId());

        SpecVersion specVersion = eventType.findSpecVersion(op.version());
        if (specVersion == null) {
            throw new NotFoundException("Version not found: " + op.version());
        }

        if (specVersion.status() == SpecVersionStatus.FINALISING) {
            throw new BadRequestException("Cannot deprecate FINALISING schemas. Finalise or delete them first.");
        }

        if (specVersion.status() == SpecVersionStatus.DEPRECATED) {
            throw new BadRequestException("Schema is already deprecated");
        }

        // Update the version status
        List<SpecVersion> updatedVersions = new ArrayList<>();
        for (SpecVersion sv : eventType.specVersions) {
            if (sv.version().equals(op.version())) {
                updatedVersions.add(sv.withStatus(SpecVersionStatus.DEPRECATED));
            } else {
                updatedVersions.add(sv);
            }
        }

        eventType.specVersions = updatedVersions;
        repo.update(eventType);
        return eventType;
    }

    private EventType doArchive(ArchiveEventType op) {
        EventType eventType = findOrThrow(op.eventTypeId());

        if (eventType.status == EventTypeStatus.ARCHIVE) {
            throw new BadRequestException("Event type is already archived");
        }

        if (!eventType.allVersionsDeprecated()) {
            throw new BadRequestException(
                "Cannot archive event type. All spec versions must be DEPRECATED first."
            );
        }

        eventType.status = EventTypeStatus.ARCHIVE;
        repo.update(eventType);
        return eventType;
    }

    private EventType doDelete(DeleteEventType op) {
        EventType eventType = findOrThrow(op.eventTypeId());

        // Can delete if archived
        if (eventType.status == EventTypeStatus.ARCHIVE) {
            repo.delete(eventType);
            return eventType; // Return for audit log, but entity is deleted
        }

        // Can delete if all schemas are still in FINALISING (never finalized)
        if (eventType.status == EventTypeStatus.CURRENT && eventType.allVersionsFinalising()) {
            repo.delete(eventType);
            return eventType;
        }

        throw new BadRequestException(
            "Cannot delete event type. Must be ARCHIVE status, or CURRENT with all schemas in FINALISING status."
        );
    }

    // ========================================================================
    // Query Methods (no audit required for reads)
    // ========================================================================

    public Optional<EventType> findById(Long id) {
        return repo.findByIdOptional(id);
    }

    public Optional<EventType> findByCode(String code) {
        return repo.findByCode(code);
    }

    public List<EventType> findAll() {
        return repo.findAllOrdered();
    }

    public List<EventType> findCurrent() {
        return repo.findCurrent();
    }

    public List<EventType> findArchived() {
        return repo.findArchived();
    }

    public List<EventType> findByCodePrefix(String prefix) {
        return repo.findByCodePrefix(prefix);
    }

    public List<String> getDistinctApplications() {
        return repo.findDistinctApplications();
    }

    public List<String> getDistinctSubdomains(String application) {
        return repo.findDistinctSubdomains(application);
    }

    public List<String> getDistinctSubdomains(List<String> applications) {
        return repo.findDistinctSubdomains(applications);
    }

    public List<String> getDistinctAggregates(String application, String subdomain) {
        return repo.findDistinctAggregates(application, subdomain);
    }

    public List<String> getDistinctAggregates(List<String> applications, List<String> subdomains) {
        return repo.findDistinctAggregates(applications, subdomains);
    }

    public List<EventType> findWithFilters(
            List<String> applications,
            List<String> subdomains,
            List<String> aggregates,
            EventTypeStatus status) {
        return repo.findWithFilters(applications, subdomains, aggregates, status);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private EventType findOrThrow(Long id) {
        return repo.findByIdOptional(id)
            .orElseThrow(() -> new NotFoundException("Event type not found: " + id));
    }

    private boolean isValidCode(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    private boolean isValidVersion(String version) {
        return version != null && VERSION_PATTERN.matcher(version).matches();
    }

    private Long extractEntityId(EventTypeOperation operation) {
        return switch (operation) {
            case CreateEventType op -> null; // ID not known yet
            case UpdateEventType op -> op.eventTypeId();
            case AddSchema op -> op.eventTypeId();
            case FinaliseSchema op -> op.eventTypeId();
            case DeprecateSchema op -> op.eventTypeId();
            case ArchiveEventType op -> op.eventTypeId();
            case DeleteEventType op -> op.eventTypeId();
        };
    }

    private void writeAuditLog(EventTypeOperation operation, Long entityId, Long principalId) {
        AuditLog log = new AuditLog();
        log.id = TsidGenerator.generate();
        log.entityType = ENTITY_TYPE;
        log.entityId = entityId;
        log.operation = operation.getClass().getSimpleName();
        log.operationJson = toJson(operation);
        log.principalId = principalId;
        log.performedAt = Instant.now();
        auditRepo.persist(log);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize operation to JSON", e);
        }
    }
}
