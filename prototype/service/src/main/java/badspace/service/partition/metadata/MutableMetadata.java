package badspace.service.partition.metadata;

import badspace.common.partition.MetadataWrite;
import badspace.service.persistent.HashTrie;

/**
 * The application metadata of the entities of the writer, which the next commit confirms.
 * It is a persistent map from entity ID to metadata: each commit makes a
 * {@link MetadataSnapshot} that shares the unchanged nodes with the commits before it,
 * because the metadata change seldom. Only the entities with metadata are in the map. It
 * does not know which entities exist: the caller checks the IDs. It has a single writer
 * and is not thread-safe.
 */
public final class MutableMetadata {

    private final HashTrie<Long, MetadataValues> byEntity = new HashTrie<>();

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
     * Closes the current commit and returns its snapshot. commitSequence must be greater
     * than the one of the last commit.
     */
    public MetadataSnapshot commit(long commitSequence) {
        return new MetadataSnapshot(byEntity.commit(commitSequence));
    }

    /**
     * Brings the metadata back to a snapshot: the writes after it are lost. The snapshot
     * must be the one of the last commit, or the empty one before the first commit.
     */
    public void revert(MetadataSnapshot committed) {
        byEntity.revert(committed.byEntity());
    }
}
