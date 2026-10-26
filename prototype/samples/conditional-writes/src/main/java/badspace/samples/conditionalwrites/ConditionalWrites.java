package badspace.samples.conditionalwrites;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.MetadataCondition.absent;
import static badspace.common.partition.MetadataWrite.put;
import static badspace.common.partition.PartitionName.partitionName;

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

/**
 * Sample client: conditional writes apply only if conditions on the metadata are true, in a
 * single step with the check. A false condition is not an error: nothing changes, and the
 * result says which conditions were false.
 */
public final class ConditionalWrites {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"));

        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            long ship = writer.insert(1, 1);
            long crate = writer.insert(5, 5);
            writer.putMetadata(ship, "version", 1);

            // A compare-and-set: the version changes only if it is still the expected one.
            System.out.printf("Version 1 -> 2: %b%n", writer.compareAndSetMetadata(ship, "version", 1, 2));
            System.out.printf("Version 1 -> 3: %b%n", writer.compareAndSetMetadata(ship, "version", 1, 3));

            // A reservation: if nobody has reserved the ship and the crate, player 7 reserves both
            // and the ship moves next to the crate.
            ConditionalWrite2 byPlayer7 = new ConditionalWrite2(
                    List.of(absent(ship, "reservedBy"), absent(crate, "reservedBy")),
                    List.of(new Entity2(ship, point(4, 5))),
                    List.of(put(ship, "reservedBy", 7), put(crate, "reservedBy", 7)));
            System.out.printf("Player 7, false conditions: %s%n", writer.updateIf(byPlayer7));

            // Player 8 finds both taken: the ship does not move and no key changes.
            ConditionalWrite2 byPlayer8 = new ConditionalWrite2(
                    List.of(absent(ship, "reservedBy"), absent(crate, "reservedBy")),
                    List.of(new Entity2(ship, point(6, 5))),
                    List.of(put(ship, "reservedBy", 8), put(crate, "reservedBy", 8)));
            System.out.printf("Player 8, false conditions: %s%n", writer.updateIf(byPlayer8));

            writer.commit();
            space.setTick(1);
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("Tick %d: ship at %s with %s, crate with %s%n", snapshot.tick(),
                        snapshot.get(ship).orElseThrow(), snapshot.getMetadata(ship).orElseThrow(),
                        snapshot.getMetadata(crate).orElseThrow());
            }
        }
    }
}
