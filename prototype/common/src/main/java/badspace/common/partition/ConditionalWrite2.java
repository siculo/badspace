package badspace.common.partition;

import java.util.List;

/**
 * A conditional write on a 2D partition: a batch that moves entities and writes their
 * application metadata only if all the conditions on the metadata are true. It is all or
 * nothing: if a condition is false nothing changes, and the result is the false conditions,
 * without an exception. Errors in the input, like an ID that is not in the partition, still
 * fail with an exception.
 *
 * @param conditions the conditions; with no conditions the write always applies
 * @param positions the new positions of existing entities, like an update; if an ID appears
 *        more than once, the last position wins
 * @param metadata the writes of the metadata, in their order
 */
public record ConditionalWrite2(List<MetadataCondition> conditions, List<Entity2> positions,
        List<MetadataWrite> metadata) {

    /** Keeps unmodifiable copies of the lists. Fails with NullPointerException if a list or an element is null. */
    public ConditionalWrite2 {
        conditions = List.copyOf(conditions);
        positions = List.copyOf(positions);
        metadata = List.copyOf(metadata);
    }
}
