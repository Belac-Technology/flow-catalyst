package tech.flowcatalyst.dispatchjob.dto;

import tech.flowcatalyst.dispatchjob.model.DispatchStatus;

import java.time.Instant;

public record DispatchJobFilter(
    DispatchStatus status,
    String source,
    String type,
    String groupId,
    Instant createdAfter,
    Instant createdBefore,
    Integer page,
    Integer size
) {
    public DispatchJobFilter {
        if (page == null || page < 0) {
            page = 0;
        }
        if (size == null || size < 1 || size > 100) {
            size = 20;
        }
    }
}
