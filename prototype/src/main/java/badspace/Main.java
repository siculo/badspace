package badspace;

/** Test client: creates a 2D and a 3D space, one partition each, and inserts entities. */
public class Main {

    public static void main(String[] args) {
        Space2 space2 = new Space2();
        Partition2 p2 = space2.createPartition();
        long a = p2.insert(10.5, 3.0);
        long b = p2.insert(-7.25, 42.0);
        System.out.printf("2D: entities %d and %d, partition size %d%n", a, b, p2.size());

        Space3 space3 = new Space3();
        Partition3 p3 = space3.createPartition();
        long c = p3.insert(1.0, 2.0, 3.0);
        System.out.printf("3D: entity %d, partition size %d%n", c, p3.size());
    }
}
