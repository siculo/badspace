package badspace.samples.entitymetadata;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.MetadataWrite.delete;
import static badspace.common.partition.MetadataWrite.put;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.service.partition.LocalPartitionNode2;
import java.util.List;

/**
 * Sample client: the application metadata of the entities, pairs of a string key and a long
 * value that the software chooses. The writer writes them through its handle and the commit
 * confirms them together with the positions. Readers read them by ID on an acquired snapshot.
 * Removing an entity deletes its metadata.
 */
public final class EntityMetadata {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Node2 node = space.createNode(LocalPartitionNode2::new);
        Partition2 partition = space.createPartition(node, partitionName("world"));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            long ship = writer.insert(1, 1);
            long bullet = writer.insert(2, 2);
            writer.putMetadata(ship, "health", 100);
            writer.putMetadata(bullet, "expiryTick", 3);
            writer.commit();
            space.setTick(1);
            show(partition, ship, bullet);

            // The ship moves and changes its metadata in the same commit. A batch changes many
            // keys of many entities in one call.
            writer.update(ship, point(5, 5));
            writer.updateMetadataAll(List.of(put(ship, "health", 80), put(ship, "team", 2)));
            writer.deleteMetadata(bullet, "expiryTick");
            writer.commit();
            space.setTick(2);
            show(partition, ship, bullet);

            writer.remove(bullet);
            writer.commit();
            space.setTick(3);
            show(partition, ship, bullet);
        }
    }

    private static void show(Partition2 partition, long ship, long bullet) {
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Tick %d: ship at %s with %s, bullet %s%n", snapshot.tick(),
                    snapshot.get(ship).orElseThrow(), snapshot.getMetadata(ship).orElseThrow(),
                    snapshot.getMetadata(bullet).map(Object::toString).orElse("removed"));
        }
    }
}
