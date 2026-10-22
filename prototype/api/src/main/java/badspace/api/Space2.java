package badspace.api;

import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.SavedPartition;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A 2-dimensional space: it is the database and the entry point of the common API.
 * Entity IDs and partition IDs come from the generators of its {@link SpaceConfig},
 * shared by all the spaces of the process, so they are unique across spaces and never
 * used again. Its partitions can live on different nodes.
 * <p>
 * The space has a tick, which it gives to the nodes on which it created partitions. A lock
 * orders the moves to a new tick and the creation of partitions, so a new node never misses
 * a tick.
 */
public final class Space2 implements Space<PartitionNode2, Partition2> {

    // Partitions may have writers on different threads, and they share the generators.
    private final SpaceConfig config;
    // Orders setTick and createPartition: both run while holding it.
    private final ReentrantLock tickLock = new ReentrantLock();
    // The nodes on which this space created partitions; each new tick goes to all of them.
    // Read and written while holding tickLock.
    private final Set<PartitionNode2> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
    // The tick of the space. Written by setTick while holding tickLock, read by any thread.
    private volatile long tick;

    /**
     * Creates a space with the given configuration. All the spaces of a process must share
     * the same generators. Fails with NullPointerException if config is null.
     */
    public Space2(SpaceConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public Partition2 createPartition(PartitionNode2 node, PartitionName name, PartitionConfig config) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(config);
        tickLock.lock();
        try {
            PartitionId id = newPartitionId(node);
            node.createPartition(id, name, config.index(), config.retainedTicks());
            return new Partition2(this, node, id, name, config);
        } finally {
            tickLock.unlock();
        }
    }

    @Override
    public Partition2 loadPartition(PartitionNode2 node, PartitionConfig config, SavedPartition saved) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(saved);
        tickLock.lock();
        try {
            PartitionId id = newPartitionId(node);
            node.loadPartition(id, saved, config.index(), config.retainedTicks());
            return new Partition2(this, node, id, saved.name(), config);
        } finally {
            tickLock.unlock();
        }
    }

    /**
     * Returns the ID of a new partition on the node, after moving the node to the tick of the
     * space if the space has no partition on it yet. The caller holds tickLock.
     */
    private PartitionId newPartitionId(PartitionNode2 node) {
        if (!nodes.contains(node)) {
            // A new node starts at tick 0, or at a tick the space gave it before.
            node.setTick(tick);
            nodes.add(node);
        }
        return new PartitionId(config.partitionIds().nextId());
    }

    @Override
    public void setTick(long tick) {
        tickLock.lock();
        try {
            if (tick < this.tick) {
                throw new IllegalArgumentException("Tick " + tick + " is less than the tick of the space " + this.tick);
            }
            this.tick = tick;
            for (PartitionNode2 node : nodes) {
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
    public List<SavedPartition> saves(PartitionNode2 node) {
        return node.saves();
    }

    @Override
    public List<SavedPartition> saves(PartitionNode2 node, PartitionName name) {
        return node.saves(name);
    }

    @Override
    public void deleteSaves(PartitionNode2 node, List<SavedPartition> saves) {
        node.deleteSaves(saves);
    }

    long nextEntityId() {
        return config.entityIds().nextId();
    }
}
