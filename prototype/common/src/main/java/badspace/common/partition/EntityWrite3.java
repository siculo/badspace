package badspace.common.partition;

import badspace.common.geometry.Point3;
import java.util.List;

/**
 * The change of one entity of a 3D partition, against the state of the partition where the
 * node applies it (the base): the last commit, with the writes sent after it. The writer does
 * not know the base, so the change says what to do in both cases, with the entity in the base
 * or not. With the buffer it is the final change of the entity after many writes; in the
 * direct mode it is a single write. It is applied in this order:
 * <ol>
 * <li>if {@code removed}, the entity leaves the partition with its application metadata, if
 *     it is there;</li>
 * <li>if {@code position} is not null, the entity is set there: it moves and keeps its
 *     metadata, or it is added with no metadata;</li>
 * <li>the writes of {@code metadataIfInBase} are applied only if the entity was in the base;
 *     they came before any set or removal of the entity, when its presence was not known;</li>
 * <li>the writes of {@code metadata} are applied: they came after a set, so the entity is
 *     there.</li>
 * </ol>
 * The result is the same as making the writes one by one, in their order, on the base. The
 * writes of each list apply in their order; the buffer leaves at most one for each key.
 *
 * @param entityId the ID of the entity
 * @param removed whether the entity leaves the partition before the other steps
 * @param position the position of the entity at the end, or null if there is no set after the
 *     last removal
 * @param metadataIfInBase the metadata writes to apply only if the entity was in the base
 * @param metadata the metadata writes to apply after the set
 */
public record EntityWrite3(long entityId, boolean removed, Point3 position,
        List<MetadataWrite> metadataIfInBase, List<MetadataWrite> metadata) {

    /** Returns the change that sets the entity at its position, for example in tests and clients. */
    public static EntityWrite3 set(Entity3 entity) {
        return new EntityWrite3(entity.id(), false, entity.position(), List.of(), List.of());
    }

    /** Returns the change that removes the entity, for example in tests and clients. */
    public static EntityWrite3 removal(long entityId) {
        return new EntityWrite3(entityId, true, null, List.of(), List.of());
    }

    /**
     * Returns the change that writes the metadata of an entity of the base, in their order, for
     * example in tests and clients. Fails like the constructor.
     */
    public static EntityWrite3 metadataOf(long entityId, List<MetadataWrite> writes) {
        return new EntityWrite3(entityId, false, null, writes, List.of());
    }

    /**
     * Copies the lists.
     *
     * @throws IllegalArgumentException if a metadata write is on another entity, if there are
     *     writes in {@code metadata} without a position, or if there are writes in
     *     {@code metadataIfInBase} with a removal
     */
    public EntityWrite3 {
        metadataIfInBase = List.copyOf(metadataIfInBase);
        metadata = List.copyOf(metadata);
        if (removed && !metadataIfInBase.isEmpty()) {
            throw new IllegalArgumentException("Metadata of the base after the removal of entity " + entityId);
        }
        if (position == null && !metadata.isEmpty()) {
            throw new IllegalArgumentException("Metadata without a position for entity " + entityId);
        }
        for (List<MetadataWrite> writes : List.of(metadataIfInBase, metadata)) {
            for (MetadataWrite write : writes) {
                if (write.entityId() != entityId) {
                    throw new IllegalArgumentException("Metadata write of entity " + write.entityId()
                            + " in the change of entity " + entityId);
                }
            }
        }
    }
}
