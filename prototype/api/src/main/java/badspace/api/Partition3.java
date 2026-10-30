package badspace.api;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionName;
import badspace.common.partition.PartitionNode3;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.WriteToken;
import java.util.List;
import java.util.Optional;

/**
 * Proxy of a 3D partition that lives on a node: it gives the information about the partition, its
 * ownership, the writer handles, the acquisitions and the saves of the snapshots. Any thread can
 * use it. The writes go through a {@link PartitionWriter3} made with {@link #writer(WriteToken)}
 * from the token of the owner. Everyone, the writer too, reads by {@linkplain #acquireLatest()
 * acquiring} the snapshot of a completed tick: the writer does not see its own writes before the
 * tick that has them moves forward, and if it must remember them, it keeps its own state.
 * <p>
 * The proxy keeps no state of the partition: the node knows the ownership and the state of
 * the removal. After the removal the acquisitions, {@link #tick()}, {@link #creationTick()}
 * and {@link #ownership()} still go to the node, which gives the snapshots of the ticks before
 * the removal while they are in the retention window; after the destruction they fail with
 * {@link badspace.common.partition.UnknownPartitionException}, like for an unknown partition.
 */
public final class Partition3 implements Partition {

    private final Space3 space;
    private final PartitionNode3 node;
    private final PartitionId partitionId;
    private final PartitionName name;
    private final PartitionConfig config;

    Partition3(Space3 space, PartitionNode3 node, PartitionId partitionId, PartitionName name, PartitionConfig config) {
        this.space = space;
        this.node = node;
        this.partitionId = partitionId;
        this.name = name;
        this.config = config;
    }

    /** Returns the space that created the partition. */
    Space3 space() {
        return space;
    }

    @Override
    public PartitionName name() {
        return name;
    }

    @Override
    public Ownership ownership() {
        return node.ownership(partitionId);
    }

    @Override
    public Optional<WriteToken> claim(long expectedGeneration) {
        return node.claim(partitionId, expectedGeneration);
    }

    @Override
    public SavedPartition save(long tick) {
        return node.save(partitionId, tick);
    }

    @Override
    public SavedPartition saveLatest() {
        return node.saveLatest(partitionId);
    }

    @Override
    public List<SavedPartition> saves() {
        return node.saves(name);
    }

    /**
     * Returns the writer handle of a token of this partition, also of a token that another
     * writer or a coordinator sent. It does not check the generation: the node checks it at
     * each operation of the handle. Fails with IllegalArgumentException if the token is of
     * another partition.
     */
    public PartitionWriter3 writer(WriteToken token) {
        if (!token.partitionId().equals(partitionId)) {
            throw new IllegalArgumentException("Token of partition " + token.partitionId()
                    + " is not of partition " + partitionId);
        }
        return new PartitionWriter3(space, node, token, config);
    }

    /** Acquires the snapshot of a tick with the default timeout, like {@link #acquire(long, AcquireOptions)}. */
    public AcquiredSnapshot3 acquire(long tick) {
        return acquire(tick, AcquireOptions.defaultTimeout());
    }

    /**
     * Acquires the snapshot of the given tick: with the tick T of the partition, the tick must
     * be from T - k to T - 1, and not before the creation of the partition. Choosing the snapshot
     * and acquiring it is a single step, so a move of the tick at the same time cannot make it
     * go away. Fails with IllegalArgumentException if tick is negative, and with
     * SnapshotNotAvailableException, with the reason, if the snapshot is not published yet, is
     * older than the window, or is before the creation of the partition. The options give the
     * timeout of the acquisition. Any thread can call it.
     */
    public AcquiredSnapshot3 acquire(long tick, AcquireOptions options) {
        return new AcquiredSnapshot3(node, node.acquire(partitionId, tick, options));
    }

    /** Acquires the latest snapshot with the default timeout, like {@link #acquireLatest(AcquireOptions)}. */
    public AcquiredSnapshot3 acquireLatest() {
        return acquireLatest(AcquireOptions.defaultTimeout());
    }

    /**
     * Acquires the snapshot of the tick before the current tick of the partition, like
     * {@link #acquire(long, AcquireOptions)}. Fails with SnapshotNotAvailableException if the
     * tick has not moved since the creation of the partition. Any thread can call it.
     */
    public AcquiredSnapshot3 acquireLatest(AcquireOptions options) {
        return new AcquiredSnapshot3(node, node.acquireLatest(partitionId, options));
    }

    @Override
    public long tick() {
        return node.tick(partitionId);
    }

    @Override
    public long creationTick() {
        return node.creationTick(partitionId);
    }

    @Override
    public PartitionConfig config() {
        return config;
    }

    @Override
    public CoordinateLimits limits() {
        return node.limits(partitionId);
    }
}
