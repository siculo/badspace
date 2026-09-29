package badspace.client;

import badspace.api.Partition2;
import badspace.api.Partition3;
import badspace.api.Space2;
import badspace.api.Space3;
import badspace.service.LocalPartitionNode2;
import badspace.service.LocalPartitionNode3;

/** Test client: creates a 2D and a 3D space with partitions on local nodes, and inserts entities. */
public class Main {

    public static void main(String[] args) {
        Space2 space2 = new Space2();
        Partition2 p2a = space2.createPartition(new LocalPartitionNode2());
        Partition2 p2b = space2.createPartition(new LocalPartitionNode2());
        long a = p2a.insert(10.5, 3.0);
        long b = p2b.insert(-7.25, 42.0);
        System.out.printf("2D: entity %d on node A, entity %d on node B%n", a, b);

        Space3 space3 = new Space3();
        Partition3 p3 = space3.createPartition(new LocalPartitionNode3());
        long c = p3.insert(1.0, 2.0, 3.0);
        System.out.printf("3D: entity %d, partition size %d%n", c, p3.size());
    }
}
