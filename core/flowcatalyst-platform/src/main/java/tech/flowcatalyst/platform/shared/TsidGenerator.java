package tech.flowcatalyst.platform.shared;

import com.github.f4b6a3.tsid.TsidCreator;

/**
 * Centralized TSID generation for all entities.
 * TSID (Time-Sorted ID) provides:
 * - Time-sortable (creation order preserved)
 * - 64-bit efficiency (vs 128-bit UUID)
 * - Sequential-ish (better for indexing than random UUIDs)
 * - Monotonic (no collisions in distributed systems)
 */
public class TsidGenerator {

    /**
     * Generate a new TSID as Long.
     */
    public static Long generate() {
        return TsidCreator.getTsid().toLong();
    }

    private TsidGenerator() {
        // Utility class
    }
}
