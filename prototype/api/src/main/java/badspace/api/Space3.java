package badspace.api;

import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.SavedPartition;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A 3-dimensional space: it is the database and the entry point of the common API.
 * Entity IDs and partition IDs come from the generators of its {@link SpaceConfig},
 * shared by all the spaces of the process, so they are unique across spaces and never
 * used again. Its partitions can live on different nodes.
 * <p>
 * The space has a tick, which it gives to the nodes on which it created partitions. A lock
 * orders the moves to a new tick and the creation of partitions, so a new node never misses
 * a tick.
 */
public final class Space3 implements Space<PartitionNode3, Partition3> {

    // Partitions may have writers on different threads, and they share the generators.
    private final SpaceConfig config;
    // Orders setTick and createPartition: both run while holding it.
    private final ReentrantLock tickLock = new ReentrantLock();
    // The nodes on which this space created partitions; each new tick goes to all of them.
    // Read and written while holding tickLock.
    private final Set<PartitionNode3> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
    // The tick of the space. Written by setTick while holding tickLock, read by any thread.
    private volatile long tick;

    /**
     * Creates a space with the given configuration. All the spaces of a process must share
     * the same generators. Fails with NullPointerException if config is null.
     */
    public Space3(SpaceConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public Partition3 createPartition(PartitionNode3 node, PartitionName name, PartitionConfig config) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(config);
        tickLock.lock();
        try {
            if (!nodes.contains(node)) {
                // A new node starts at tick 0, or at a tick the space gave it before.
                node.setTick(tick);
                nodes.add(node);
            }
            PartitionId id = new PartitionId(this.config.partitionIds().nextId());
            node.createPartition(id, name, config.index(), config.retainedTicks());
            return new Partition3(this, node, id, name, config);
        } finally {
            tickLock.unlock();
        }
    }

    @Override
    public void setTick(long tick) {
        tickLock.lock();
        try {
            if (tick < this.tick) {
                throw new IllegalArgumentException("Tick " + tick + " is less than the tick of the space " + this.tick);
            }
            this.tick = tick;
            for (PartitionNode3 node : nodes) {
                node.setTick(tick);
            }
        } finally {
            tickLock.unlock();
        }
    }

    @Override
    public long tick() {
        return tick;
    }

    @Override
    public List<SavedPartition> saves(PartitionNode3 node) {
        return node.saves();
    }

    @Override
    public List<SavedPartition> saves(PartitionNode3 node, PartitionName name) {
        return node.saves(name);
    }

    @Override
    public void deleteSaves(PartitionNode3 node, List<SavedPartition> saves) {
        node.deleteSaves(saves);
    }

    long nextEntityId() {
        return config.entityIds().nextId();
    }
}
