package badspace.common.partition;

import badspace.common.geometry.Point2;
import java.util.List;

/**
 * The final change of one entity of a 2D partition after many writes, against the last
 * committed state of the partition (the base). The writer does not know the base, so the
 * change says what to do in both cases, with the entity in the base or not. It is applied in
 * this order:
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
 * The result is the same as making the writes one by one, in their order, on the base. Each
 * list has at most one write for each key.
 *
 * @param entityId the ID of the entity
 * @param removed whether the entity leaves the partition before the other steps
 * @param position the position of the entity at the end, or null if there is no set after the
 *     last removal
 * @param metadataIfInBase the metadata writes to apply only if the entity was in the base
 * @param metadata the metadata writes to apply after the set
 */
public record EntityWrite2(long entityId, boolean removed, Point2 position,
        List<MetadataWrite> metadataIfInBase, List<MetadataWrite> metadata) {

    /** Returns the change that sets the entity at its position, for example in tests and clients. */
    public static EntityWrite2 set(Entity2 entity) {
        return new EntityWrite2(entity.id(), false, entity.position(), List.of(), List.of());
    }

    /** Returns the change that removes the entity, for example in tests and clients. */
    public static EntityWrite2 removal(long entityId) {
        return new EntityWrite2(entityId, true, null, List.of(), List.of());
    }

    /**
     * Returns the change that writes the metadata of an entity of the base, in their order, for
     * example in tests and clients. Fails like the constructor.
     */
    public static EntityWrite2 metadataOf(long entityId, List<MetadataWrite> writes) {
        return new EntityWrite2(entityId, false, null, writes, List.of());
    }

    /**
     * Copies the lists.
     *
     * @throws IllegalArgumentException if a metadata write is on another entity, if there are
     *     writes in {@code metadata} without a position, or if there are writes in
     *     {@code metadataIfInBase} with a removal
     */
    public EntityWrite2 {
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
