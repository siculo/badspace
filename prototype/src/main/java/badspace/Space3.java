package badspace;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A 3-dimensional space: it is the database and the entry point of the common API.
 * It generates entity IDs that are unique across all its partitions.
 */
public final class Space3 {

    // Partitions may have writers on different threads, and they share this generator.
    private final AtomicLong nextId = new AtomicLong(1);

    public Partition3 createPartition() {
        return new Partition3(this);
    }

    long nextId() {
        return nextId.getAndIncrement();
    }
}
