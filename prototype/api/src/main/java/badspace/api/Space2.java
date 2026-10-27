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
 * The space creates its nodes and gives its tick to all of them, also to the nodes with no
 * partitions. A lock orders the moves to a new tick, the addition of a node and the creation of
 * partitions, so a new node never misses a tick. A load does not take the lock, so the moves of
 * the tick do not wait for it.
 */
public final class Space2 implements Space<Node2, Partition2, PartitionNodeFactory2> {

    // Partitions may have writers on different threads, and they share the generators.
    private final SpaceConfig config;
    // Orders setTick, the addition of a node and createPartition: they run while holding it.
    private final ReentrantLock tickLock = new ReentrantLock();
    // The nodes created by this space; each new tick goes to all of them. Read and written while
    // holding tickLock.
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
    public Node2 createNode(PartitionNodeFactory2 factory) {
        Objects.requireNonNull(factory);
        // Outside the lock: building a node can take time, and no other thread sees it yet.
        PartitionNode2 node = factory.create();
        if (node == null) {
            throw new IllegalArgumentException("The factory returned no node");
        }
        tickLock.lock();
        try {
            if (nodes.contains(node)) {
                throw new IllegalArgumentException("The factory returned a node that is already in the space");
            }
            // Added only after the move: a node that fails to move is not in the space.
            node.setTick(tick);
            nodes.add(node);
        } finally {
            tickLock.unlock();
        }
        return new Node2(this, node);
    }

    @Override
    public Partition2 createPartition(Node2 node, PartitionName name, PartitionConfig config) {
        PartitionNode2 partitionNode = nodeOf(node);
        Objects.requireNonNull(name);
        Objects.requireNonNull(config);
        tickLock.lock();
        try {
            PartitionId id = newPartitionId();
            partitionNode.createPartition(id, name, config.index(), config.retainedTicks());
            return new Partition2(this, partitionNode, id, name, config);
        } finally {
            tickLock.unlock();
        }
    }

    @Override
    public Partition2 loadPartition(Node2 node, PartitionConfig config, SavedPartition saved) {
        PartitionNode2 partitionNode = nodeOf(node);
        Objects.requireNonNull(config);
        Objects.requireNonNull(saved);
        // Without the lock: the moves of the tick do not wait for the load. The node is already
        // among the nodes of the space, so it gets them, and it creates the partition at its tick.
        PartitionId id = newPartitionId();
        partitionNode.loadPartition(id, saved, config.index(), config.retainedTicks());
        return new Partition2(this, partitionNode, id, saved.name(), config);
    }

    /** Returns the ID of a new partition. The generator is safe across threads. */
    private PartitionId newPartitionId() {
        return new PartitionId(config.partitionIds().nextId());
    }

    /**
     * Returns the node of the handle. Fails with IllegalArgumentException if the handle belongs
     * to another space: the check uses the identity of the space, so two spaces with the same
     * configuration are still different.
     */
    private PartitionNode2 nodeOf(Node2 node) {
        if (node.space() != this) {
            throw new IllegalArgumentException("The node belongs to another space");
        }
        return node.node();
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
    public List<SavedPartition> saves(Node2 node) {
        return nodeOf(node).saves();
    }

    @Override
    public List<SavedPartition> saves(Node2 node, PartitionName name) {
        return nodeOf(node).saves(name);
    }

    @Override
    public void deleteSaves(Node2 node, List<SavedPartition> saves) {
        nodeOf(node).deleteSaves(saves);
    }

    long nextEntityId() {
        return config.entityIds().nextId();
    }
}
