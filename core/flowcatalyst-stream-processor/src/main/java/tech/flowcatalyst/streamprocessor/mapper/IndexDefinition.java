package tech.flowcatalyst.streamprocessor.mapper;

import com.mongodb.client.model.IndexOptions;
import org.bson.conversions.Bson;

/**
 * Definition of a MongoDB index to be created on a projection collection.
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * new IndexDefinition("status", Indexes.ascending("status"))
 * new IndexDefinition("correlationId",
 *     Indexes.ascending("correlationId"),
 *     new IndexOptions().sparse(true))
 * }</pre>
 *
 * @param name    unique name for this index
 * @param keys    the index keys (use {@link com.mongodb.client.model.Indexes} helpers)
 * @param options optional index options (sparse, unique, TTL, etc.)
 */
public record IndexDefinition(
        String name,
        Bson keys,
        IndexOptions options
) {
    /**
     * Create an index definition with default options.
     *
     * @param name unique name for this index
     * @param keys the index keys
     */
    public IndexDefinition(String name, Bson keys) {
        this(name, keys, new IndexOptions());
    }
}
