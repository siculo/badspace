package badspace.service.partition;

import badspace.common.partition.Entity3;
import badspace.common.partition.EntityWrite3;
import badspace.common.partition.MetadataWrite;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives the tests of the 3D storage the batches of {@link PartitionStorage3#apply}: one change
 * for each entity, in the format of {@link EntityWrite3}, built from the lists of the tests.
 */
final class Changes3 {

    private Changes3() {
    }

    /** Returns the changes that set each entity at its position. */
    static List<EntityWrite3> sets(List<Entity3> entities) {
        List<EntityWrite3> changes = new ArrayList<>(entities.size());
        for (Entity3 entity : entities) {
            changes.add(EntityWrite3.set(entity));
        }
        return changes;
    }

    /** Returns the changes that remove each entity. */
    static List<EntityWrite3> removals(long[] entityIds) {
        List<EntityWrite3> changes = new ArrayList<>(entityIds.length);
        for (long entityId : entityIds) {
            changes.add(EntityWrite3.removal(entityId));
        }
        return changes;
    }

    /**
     * Returns the changes that write the metadata of entities in the storage, one for each
     * entity with its writes in their order.
     */
    static List<EntityWrite3> metadata(List<MetadataWrite> writes) {
        Map<Long, List<MetadataWrite>> byEntity = new LinkedHashMap<>();
        for (MetadataWrite write : writes) {
            byEntity.computeIfAbsent(write.entityId(), id -> new ArrayList<>()).add(write);
        }
        List<EntityWrite3> changes = new ArrayList<>(byEntity.size());
        for (Map.Entry<Long, List<MetadataWrite>> entry : byEntity.entrySet()) {
            changes.add(EntityWrite3.metadataOf(entry.getKey(), entry.getValue()));
        }
        return changes;
    }
}
