package badspace.service.partition;

import badspace.common.partition.Entity2;
import badspace.common.partition.EntityWrite2;
import badspace.common.partition.MetadataWrite;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives the tests of the 2D storage the batches of {@link PartitionStorage2#apply}: one change
 * for each entity, in the format of {@link EntityWrite2}, built from the lists of the tests.
 */
final class Changes2 {

    private Changes2() {
    }

    /** Returns the changes that set each entity at its position. */
    static List<EntityWrite2> sets(List<Entity2> entities) {
        List<EntityWrite2> changes = new ArrayList<>(entities.size());
        for (Entity2 entity : entities) {
            changes.add(EntityWrite2.set(entity));
        }
        return changes;
    }

    /** Returns the changes that remove each entity. */
    static List<EntityWrite2> removals(long[] entityIds) {
        List<EntityWrite2> changes = new ArrayList<>(entityIds.length);
        for (long entityId : entityIds) {
            changes.add(EntityWrite2.removal(entityId));
        }
        return changes;
    }

    /**
     * Returns the changes that write the metadata of entities in the storage, one for each
     * entity with its writes in their order.
     */
    static List<EntityWrite2> metadata(List<MetadataWrite> writes) {
        Map<Long, List<MetadataWrite>> byEntity = new LinkedHashMap<>();
        for (MetadataWrite write : writes) {
            byEntity.computeIfAbsent(write.entityId(), id -> new ArrayList<>()).add(write);
        }
        List<EntityWrite2> changes = new ArrayList<>(byEntity.size());
        for (Map.Entry<Long, List<MetadataWrite>> entry : byEntity.entrySet()) {
            changes.add(EntityWrite2.metadataOf(entry.getKey(), entry.getValue()));
        }
        return changes;
    }
}
