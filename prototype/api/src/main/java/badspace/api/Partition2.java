package badspace.api;

import badspace.common.partition.AcquireOptions;
import badspace.common.partition.CoordinateLimits;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.PartitionNode2;
import badspace.common.partition.WriteToken;
import java.util.Optional;

/**
 * Proxy of a 2D partition that lives on a node: it gives the information about the partition,
 * its ownership, the writer handles and the acquisitions of the snapshots. Any thread can use
 * it. The writes go through a {@link PartitionWriter2} made with {@link #writer(WriteToken)}
 * from the token of the owner. Everyone, the writer too, reads by
 * {@linkplain #acquireLatest() acquiring} the snapshot of a completed tick: the writer does not
 * see its own writes before the tick that has them moves forward, and if it must remember them,
 * it keeps its own state.
 * <p>
 * The proxy keeps no state of the partition: the node knows the ownership and the state of
 * the removal. After the removal the acquisitions, {@link #tick()}, {@link #creationTick()}
 * and {@link #ownership()} still go to the node, which gives the snapshots of the ticks before
 * the removal while they are in the retention window; after the destruction they fail like for
 * an unknown partition.
 */
public final class Partition2 implements Partition {

    private final Space2 space;
    private final PartitionNode2 node;
    private final PartitionId partitionId;
    private final PartitionConfig config;

    Partition2(Space2 space, PartitionNode2 node, PartitionId partitionId, PartitionConfig config) {
        this.space = space;
        this.node = node;
        this.partitionId = partitionId;
        this.config = config;
    }

    @Override
    public Ownership ownership() {
        return node.ownership(partitionId);
    }

    @Override
    public Optional<WriteToken> claim(long expectedGeneration) {
        return node.claim(partitionId, expectedGeneration);
    }

    /**
     * Returns the writer handle of a token of this partition, also of a token that another
     * writer or a coordinator sent. It does not check the generation: the node checks it at
     * each operation of the handle. Fails with IllegalArgumentException if the token is of
     * another partition.
     */
    public PartitionWriter2 writer(WriteToken token) {
        if (!token.partitionId().equals(partitionId)) {
            throw new IllegalArgumentException("Token of partition " + token.partitionId()
                    + " is not of partition " + partitionId);
        }
        return new PartitionWriter2(space, node, token, config);
    }

    /** Acquires the snapshot of a tick with the default timeout, like {@link #acquire(long, AcquireOptions)}. */
    public AcquiredSnapshot2 acquire(long tick) {
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
    public AcquiredSnapshot2 acquire(long tick, AcquireOptions options) {
        return new AcquiredSnapshot2(node, node.acquire(partitionId, tick, options));
    }

    /** Acquires the latest snapshot with the default timeout, like {@link #acquireLatest(AcquireOptions)}. */
    public AcquiredSnapshot2 acquireLatest() {
        return acquireLatest(AcquireOptions.defaultTimeout());
    }

    /**
     * Acquires the snapshot of the tick before the current tick of the partition, like
     * {@link #acquire(long, AcquireOptions)}. Fails with SnapshotNotAvailableException if the
     * tick has not moved since the creation of the partition. Any thread can call it.
     */
    public AcquiredSnapshot2 acquireLatest(AcquireOptions options) {
        return new AcquiredSnapshot2(node, node.acquireLatest(partitionId, options));
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
