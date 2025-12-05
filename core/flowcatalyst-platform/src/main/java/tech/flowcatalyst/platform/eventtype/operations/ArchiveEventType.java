package tech.flowcatalyst.platform.eventtype.operations;

/**
 * Operation to archive an EventType.
 *
 * Changes status from CURRENT → ARCHIVE.
 * Can only archive when ALL spec versions are DEPRECATED.
 *
 * @param eventTypeId The EventType to archive
 */
public record ArchiveEventType(
    Long eventTypeId
) implements EventTypeOperation {
}
