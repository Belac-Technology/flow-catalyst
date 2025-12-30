package tech.flowcatalyst.platform.shared;

import com.github.f4b6a3.tsid.Tsid;
import com.github.f4b6a3.tsid.TsidCreator;

/**
 * Centralized TSID generation for all entities.
 * TSID (Time-Sorted ID) provides:
 * - Time-sortable (creation order preserved)
 * - 64-bit efficiency (vs 128-bit UUID)
 * - Sequential-ish (better for indexing than random UUIDs)
 * - Monotonic (no collisions in distributed systems)
 *
 * IDs are stored and transmitted as Crockford Base32 strings (13 chars).
 * Example: "0HZXEQ5Y8JY5Z"
 *
 * This format is:
 * - Shorter than numeric strings (13 vs ~19 chars)
 * - URL-safe and case-insensitive
 * - Lexicographically sortable (preserves time order)
 * - Safe from JavaScript number precision issues
 */
public class TsidGenerator {

    /**
     * Generate a new TSID as a Crockford Base32 string.
     * This is the preferred format for storage and API responses.
     */
    public static String generate() {
        return TsidCreator.getTsid().toString();
    }

    /**
     * Convert a TSID string to Long.
     * Useful for database queries on legacy Long fields.
     */
    public static Long toLong(String tsidString) {
        return Tsid.from(tsidString).toLong();
    }

    /**
     * Convert a Long to TSID string.
     * Useful for migrating existing Long IDs to string format.
     */
    public static String toString(Long tsidLong) {
        return Tsid.from(tsidLong).toString();
    }

    private TsidGenerator() {
        // Utility class
    }
}
