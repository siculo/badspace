package badspace.service.partition.metadata;

import badspace.common.partition.ApplicationMetadata;
import badspace.service.persistent.HashTrie;
import java.util.Map;

/**
 * The application metadata of a partition at a commit. It never changes, so many threads
 * can read it while the writer goes on. A commit without changes of the metadata shares
 * the nodes of the snapshot before it.
 */
public final class MetadataSnapshot {

    private final HashTrie.View<Long, MetadataValues> byEntity;

    MetadataSnapshot(HashTrie.View<Long, MetadataValues> byEntity) {
        this.byEntity = byEntity;
    }

    /** Returns the metadata of a new partition, before the first commit: no entity has metadata. */
    public static MetadataSnapshot empty() {
        return new MetadataSnapshot(HashTrie.View.empty());
    }

    /**
     * Returns the metadata of an entity, with an empty map if it has none. It does not check
     * that the entity is in the partition: the caller checks it in the slots.
     */
    public ApplicationMetadata metadataOf(long entityId) {
        MetadataValues values = byEntity.get(entityId);
        return new ApplicationMetadata(entityId, values != null ? values.toMap() : Map.of());
    }

    HashTrie.View<Long, MetadataValues> byEntity() {
        return byEntity;
    }
}
