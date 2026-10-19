package badspace.samples.entitymetadata;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.MetadataCondition.absent;
import static badspace.common.partition.MetadataWrite.delete;
import static badspace.common.partition.MetadataWrite.put;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.ConditionalWrite2;
import badspace.common.partition.Entity2;
import badspace.service.partition.LocalPartitionNode2;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Sample client: the application metadata of the entities, pairs of a string key and a long
 * value that the software chooses, for example a version or the tick when an entity expires.
 * The writer writes them through its handle, and the commit confirms them together with the
 * positions. Readers read them by ID on an acquired snapshot, with a read separate from the
 * spatial queries. Conditional writes apply only if conditions on the metadata are true, for
 * example a compare-and-set of a version or the reservation of two entities: a false condition
 * changes nothing and is returned, without an exception. Removing an entity deletes its metadata.
 */
public final class EntityMetadata {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2());

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            long ship = writer.insert(1, 1);
            long bullet = writer.insert(2, 2);
            // Single writes are batches of one write.
            writer.putMetadata(ship, "version", 1);
            writer.putMetadata(bullet, "expiryTick", 3);
            writer.commit();
            space.setTick(1);
            show(partition, ship, bullet);

            // A batch changes many keys of many entities in one call; for the same key the last
            // write wins. The metadata change in the same commit as the positions.
            writer.update(ship, point(5, 5));
            writer.updateMetadataAll(List.of(put(ship, "version", 2), put(ship, "owner", 42),
                    put(ship, "version", 3), delete(bullet, "expiryTick")));
            writer.commit();
            space.setTick(2);
            show(partition, ship, bullet);

            // A batch is all-or-nothing: an unknown entity rejects the whole batch.
            try {
                writer.updateMetadataAll(List.of(put(ship, "version", 4), put(bullet + ship, "version", 1)));
            } catch (NoSuchElementException e) {
                System.out.printf("Rejected batch, the version of %d does not change: %s%n", ship, e.getMessage());
            }

            // A compare-and-set: it sets the version only if it is still the expected one.
            System.out.printf("Version 3 -> 4: %b, version 3 -> 5: %b%n",
                    writer.compareAndSetMetadata(ship, "version", 3, 4),
                    writer.compareAndSetMetadata(ship, "version", 3, 5));

            // A reservation of two entities: both must be free, and the ship moves next to the
            // bullet in the same step. The second reservation finds them taken and changes nothing.
            ConditionalWrite2 reserve = new ConditionalWrite2(
                    List.of(absent(ship, "reservedBy"), absent(bullet, "reservedBy")),
                    List.of(new Entity2(ship, point(2, 3))),
                    List.of(put(ship, "reservedBy", 7), put(bullet, "reservedBy", 7)));
            System.out.printf("First reservation, false conditions: %s%n", writer.updateIf(reserve));
            System.out.printf("Second reservation, false conditions: %s%n", writer.updateIf(reserve));
            writer.commit();
            space.setTick(3);
            show(partition, ship, bullet);

            // Removing an entity deletes its metadata: the ID is no longer in the snapshot.
            writer.remove(bullet);
            writer.commit();
            space.setTick(4);
            show(partition, ship, bullet);
        }
    }

    private static void show(Partition2 partition, long ship, long bullet) {
        try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
            System.out.printf("Snapshot of tick %d: ship at %s with %s, bullet %s%n", snapshot.tick(),
                    snapshot.get(ship).orElseThrow(), snapshot.getMetadata(ship).orElseThrow(),
                    snapshot.getMetadata(bullet).map(Object::toString).orElse("removed"));
        }
    }
}
