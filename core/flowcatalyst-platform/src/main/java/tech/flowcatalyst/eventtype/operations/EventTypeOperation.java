package tech.flowcatalyst.eventtype.operations;

import tech.flowcatalyst.platform.audit.Audited;

/**
 * Sealed interface for all EventType operations.
 *
 * All operations on EventType must go through EventTypeService.execute()
 * which enforces audit logging. This sealed interface ensures compile-time
 * exhaustive matching in the service's switch expression.
 */
public sealed interface EventTypeOperation extends Audited
    permits CreateEventType, UpdateEventType, AddSchema,
            FinaliseSchema, DeprecateSchema, ArchiveEventType, DeleteEventType {
}
