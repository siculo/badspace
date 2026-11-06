package badspace.samples.writemodes;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Node2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.WriteMode;
import badspace.common.partition.WriteToken;
import badspace.service.partition.LocalPartitionNode2;
import badspace.service.partition.PartitionNodeConfig;

/**
 * Sample client: the write mode of a node decides when the writes of the writer reach it. A
 * node in the same process is in the direct mode: each write goes to the node at once, and the
 * commit confirms them. A remote node would be in the buffered mode: the writer keeps the
 * writes and the commit sends them in one call. Here a local node in the buffered mode stands
 * for the remote one. The same writes make the same commits in the two modes; only the moment
 * of a rejection changes, for example after the ownership went to another writer. With the
 * buffer the writer can also send its writes in pieces with a flush, before the commit.
 */
public final class WriteModes {

    public static void main(String[] args) {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        for (WriteMode mode : WriteMode.values()) {
            Node2 node = space.createNode(() -> new LocalPartitionNode2(PartitionNodeConfig.of().withWriteMode(mode)));
            Partition2 partition = space.createPartition(node, partitionName("world-" + mode.name().toLowerCase()));
            WriteToken token = partition.claim(0).orElseThrow();
            PartitionWriter2 writer = partition.writer(token);

            // The same writes in the two modes: a ship that moves, and a crate that the writer
            // adds and then removes before the commit.
            long ship = writer.insert(1, 1);
            writer.set(ship, point(2, 2));
            writer.putMetadata(ship, "fuel", 90);
            long crate = writer.insert(5, 5);
            writer.remove(crate);
            writer.commit();
            space.setTick(space.tick() + 1);
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("%s: %d entities, ship at %s with %s%n", mode, snapshot.size(),
                        snapshot.get(ship).orElseThrow(), snapshot.getMetadata(ship).orElseThrow());
            }

            // Many writes in pieces: with the buffer each flush sends a piece to the node, so the
            // buffer stays small; in the direct mode the flush does nothing. The move of the
            // tick between two pieces publishes only the last commit, and the commit confirms
            // all the pieces together.
            long[] cargo = new long[3];
            for (int i = 0; i < cargo.length; i++) {
                cargo[i] = writer.insert(10 + i, 10);
                writer.flush();
                space.setTick(space.tick() + 1);
            }
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("%s: before the commit of the pieces, %d entities%n", mode, snapshot.size());
            }
            writer.commit();
            space.setTick(space.tick() + 1);
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("%s: after the commit of the pieces, %d entities%n", mode, snapshot.size());
            }

            // Another writer takes the ownership. The old writer finds out at its next write in
            // the direct mode, and only at the commit with the buffer: in both cases its write
            // never reaches the partition.
            try (PartitionWriter2 next = partition.writer(partition.claim(token.generation()).orElseThrow())) {
                try {
                    writer.set(ship, point(9, 9));
                    System.out.printf("%s: the write of the old writer stays in its buffer%n", mode);
                    writer.commit();
                } catch (OwnershipChangedException e) {
                    System.out.printf("%s: rejected: %s%n", mode, e.getMessage());
                }
                next.commit();
            }
            space.setTick(space.tick() + 1);
            try (AcquiredSnapshot2 snapshot = partition.acquireLatest()) {
                System.out.printf("%s: ship still at %s%n", mode, snapshot.get(ship).orElseThrow());
            }
            // The token of the old writer is not current: its close does nothing.
            writer.close();
        }
    }
}
