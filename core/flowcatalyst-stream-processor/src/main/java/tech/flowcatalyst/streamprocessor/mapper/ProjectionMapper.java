package tech.flowcatalyst.streamprocessor.mapper;

import org.bson.Document;

import java.util.List;

/**
 * Interface for stream-specific projection mapping.
 *
 * <p>Implementations must be CDI beans with {@code @Named} qualifier matching
 * the mapper name in configuration. For example:</p>
 *
 * <pre>{@code
 * @ApplicationScoped
 * @Named("events")
 * public class EventProjectionMapper implements ProjectionMapper {
 *     // ...
 * }
 * }</pre>
 *
 * <p>The mapper is responsible for:</p>
 * <ul>
 *   <li>Transforming source documents into the projection format</li>
 *   <li>Defining indexes for the projection collection</li>
 *   <li>Ensuring the projected document has _id set for idempotency</li>
 * </ul>
 */
public interface ProjectionMapper {

    /**
     * Transform a source document into the projection format.
     *
     * <p>The returned document MUST have an _id field set for idempotency.
     * Typically this is derived from the source document's _id.</p>
     *
     * <p>The mapper can:</p>
     * <ul>
     *   <li>Copy fields directly from source</li>
     *   <li>Rename or reshape fields</li>
     *   <li>Add computed fields (e.g., projectedAt timestamp)</li>
     *   <li>Exclude large or unnecessary fields</li>
     *   <li>Flatten nested structures</li>
     * </ul>
     *
     * @param source the raw document from the change stream
     * @return the projected document to write to the projection collection
     */
    Document toProjection(Document source);

    /**
     * Define indexes for the projection collection.
     *
     * <p>Called once on startup. Indexes are created idempotently
     * (existing indexes with the same name are not recreated).</p>
     *
     * @return list of index definitions for this projection
     */
    List<IndexDefinition> getIndexDefinitions();

    /**
     * Name of this mapper (for logging and debugging).
     *
     * @return human-readable name of this mapper
     */
    String getName();
}
