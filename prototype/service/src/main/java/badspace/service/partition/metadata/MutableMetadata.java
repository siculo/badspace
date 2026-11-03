package badspace.service.partition.metadata;

import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.MetadataCondition;
import badspace.common.partition.MetadataWrite;
import badspace.service.persistent.HashTrie;
import badspace.service.transaction.TransactionalComponent;
import java.util.Map;

/**
 * The application metadata of the entities of the writer, which the next commit confirms.
 * It is a persistent map from entity ID to metadata: each commit makes a
 * {@link MetadataSnapshot} that shares the unchanged nodes with the commits before it,
 * because the metadata change seldom. Only the entities with metadata are in the map. It
 * does not know which entities exist: the caller checks the IDs. It has a single writer
 * and is not thread-safe.
 */
public final class MutableMetadata implements TransactionalComponent<MetadataSnapshot> {

    private final HashTrie<Long, MetadataValues> byEntity = new HashTrie<>();

    /** Returns whether the condition is true on the metadata of the writer. */
    public boolean holds(MetadataCondition condition) {
        MetadataValues values = byEntity.get(condition.entityId());
        return switch (condition) {
            case MetadataCondition.ValueIs valueIs -> values != null && values.hasValue(valueIs.key(), valueIs.value());
            case MetadataCondition.Absent absent -> values == null || !values.contains(absent.key());
        };
    }

    /** Applies a write to the metadata of its entity. */
    public void write(MetadataWrite write) {
        long entityId = write.entityId();
        MetadataValues current = byEntity.get(entityId);
        if (current == null) {
            current = MetadataValues.EMPTY;
        }
        MetadataValues next = switch (write) {
            case MetadataWrite.Put put -> current.with(put.key(), put.value());
            case MetadataWrite.Delete delete -> current.without(delete.key());
        };
        if (next == current) {
            return;
        }
        if (next.isEmpty()) {
            byEntity.remove(entityId);
        } else {
            byEntity.put(entityId, next);
        }
    }

    /** Deletes all the metadata of a removed entity. */
    public void removeEntity(long entityId) {
        byEntity.remove(entityId);
    }

    /**
     * Returns the metadata of an entity in the working state, with an empty map if it has
     * none. Only for the tests.
     */
    ApplicationMetadata metadataOf(long entityId) {
        MetadataValues values = byEntity.get(entityId);
        return new ApplicationMetadata(entityId, values != null ? values.toMap() : Map.of());
    }

    /**
     * Closes the current commit and returns its snapshot. After it, the persistent map copies
     * its nodes before it changes them.
     */
    @Override
    public MetadataSnapshot prepareCommit(long commitSequence) {
        return new MetadataSnapshot(byEntity.commit(commitSequence));
    }

    /** Takes again the map of the snapshot: the cost does not depend on the number of entities. */
    @Override
    public void acceptCommit(MetadataSnapshot prepared) {
        // Nothing to do: the preparation already made the nodes of the snapshot read-only.
    }

    @Override
    public void abandonCommit(MetadataSnapshot prepared) {
        // Nothing to give back: the memory of the snapshot is freed when nobody refers to it.
    }

    @Override
    public void restore(MetadataSnapshot confirmed) {
        byEntity.revert(confirmed.byEntity());
    }
}
