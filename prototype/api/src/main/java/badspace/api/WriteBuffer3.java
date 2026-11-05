package badspace.api;

import badspace.common.geometry.Point3;
import badspace.common.partition.Entity3;
import badspace.common.partition.EntityWrite3;
import badspace.common.partition.MetadataWrite;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The writes of a 3D writer not sent yet, composed by entity: for each entity it keeps only
 * the final change against the base, not the list of the calls (see {@link EntityWrite3}).
 * The result is the same as making the writes one by one, in their order: a set moves the
 * entity or adds it with the same ID, a removal deletes it with its metadata, a metadata write
 * on an entity that is not there does nothing. The buffer does not know the base, so a
 * metadata write before any set or removal of its entity stays for the case that the entity
 * is in the base. The buffer does not check the positions against the limits. It is not
 * thread-safe.
 */
final class WriteBuffer3 {

    /** The change of one entity while the buffer composes it. */
    private static final class Change {

        // True after a removal: the entity of the base, if any, leaves the partition.
        boolean removed;
        // The position of the last set after the last removal, or null.
        Point3 position;
        // The last write of each key before any set or removal, and after the last set.
        final Map<String, MetadataWrite> metadataIfInBase = new LinkedHashMap<>();
        final Map<String, MetadataWrite> metadata = new LinkedHashMap<>();
    }

    private final Map<Long, Change> byEntity = new LinkedHashMap<>();

    /** Sets the position of the entity: it moves, or it is added with the same ID. */
    void set(Entity3 entity) {
        change(entity.id()).position = entity.position();
    }

    /** Removes the entity with its metadata; if the entity is not there, it does nothing. */
    void remove(long entityId) {
        Change change = change(entityId);
        change.removed = true;
        change.position = null;
        change.metadataIfInBase.clear();
        change.metadata.clear();
    }

    /** Writes one key of the metadata of an entity; if the entity is not there, it does nothing. */
    void write(MetadataWrite write) {
        Change change = change(write.entityId());
        if (change.position != null) {
            // After a set the entity is there, whatever the base: the write is applied, and
            // it replaces a write of the same key that depends on the base.
            change.metadataIfInBase.remove(write.key());
            change.metadata.put(write.key(), write);
        } else if (!change.removed) {
            change.metadataIfInBase.put(write.key(), write);
        }
        // After a removal and before a set the entity is not there: the write does nothing.
    }

    /** Returns whether the buffer has no writes. */
    boolean isEmpty() {
        return byEntity.isEmpty();
    }

    /**
     * Returns the final changes, one for each entity with writes, in the order of the first
     * write of each entity. An entity whose writes do nothing, like metadata writes after a
     * removal, can still have a change.
     */
    List<EntityWrite3> changes() {
        List<EntityWrite3> changes = new ArrayList<>(byEntity.size());
        for (Map.Entry<Long, Change> entry : byEntity.entrySet()) {
            Change change = entry.getValue();
            changes.add(new EntityWrite3(entry.getKey(), change.removed, change.position,
                    List.copyOf(change.metadataIfInBase.values()), List.copyOf(change.metadata.values())));
        }
        return changes;
    }

    private Change change(long entityId) {
        return byEntity.computeIfAbsent(entityId, id -> new Change());
    }
}
