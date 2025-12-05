package tech.flowcatalyst.platform.eventtype;

/**
 * A versioned schema specification for an EventType.
 *
 * Stored as JSON within the EventType entity.
 *
 * Version format: {MAJOR}.{MINOR}
 * - Minor versions (1.0 → 1.1) are backwards compatible
 * - Major versions (1.x → 2.0) are breaking changes
 *
 * @param version    Version string in format "MAJOR.MINOR" (e.g., "1.0", "1.1", "2.0")
 * @param mimeType   MIME type for the event payload (e.g., "application/json", "application/protobuf")
 * @param schema     The schema definition content (JSON Schema, Proto, or XSD)
 * @param schemaType The type of schema (JSON_SCHEMA, PROTO, XSD)
 * @param status     Current status of this spec version (FINALISING, CURRENT, DEPRECATED)
 */
public record SpecVersion(
    String version,
    String mimeType,
    String schema,
    SchemaType schemaType,
    SpecVersionStatus status
) {

    /**
     * Extract the major version number from the version string.
     * e.g., "1.2" → 1
     */
    public int majorVersion() {
        if (version == null || !version.contains(".")) {
            throw new IllegalStateException("Invalid version format: " + version);
        }
        return Integer.parseInt(version.split("\\.")[0]);
    }

    /**
     * Extract the minor version number from the version string.
     * e.g., "1.2" → 2
     */
    public int minorVersion() {
        if (version == null || !version.contains(".")) {
            throw new IllegalStateException("Invalid version format: " + version);
        }
        return Integer.parseInt(version.split("\\.")[1]);
    }

    /**
     * Create a copy with a new status.
     */
    public SpecVersion withStatus(SpecVersionStatus newStatus) {
        return new SpecVersion(version, mimeType, schema, schemaType, newStatus);
    }
}
