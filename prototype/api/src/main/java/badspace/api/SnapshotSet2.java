package badspace.api;

import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import badspace.common.partition.AcquireOptions;
import badspace.common.partition.Entity2;
import badspace.common.partition.RetentionWindow;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.common.partition.SnapshotNotAvailableException.Reason;
import badspace.common.partition.UnknownPartitionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The snapshots of the same tick acquired on a set of 2D partitions, with the queries on all of
 * them. The caller chooses the partitions and the tick, and decides what to do with the
 * partitions whose snapshot is not available: the set acquires what it can and gives the
 * {@linkplain #failures() failures}, so the same set can give a complete or a partial result.
 * Use it with try-with-resources: {@link #close()} releases all the acquisitions.
 * <p>
 * The snapshots are acquired one after the other: each partition publishes its snapshots on its
 * own, so the snapshot of the tick can be available on some partitions and not yet, or no
 * longer, on others. The queries see only the acquired snapshots. An entity is in one partition
 * only, so the queries do not remove duplicates. Like the views of the snapshots, the set can be
 * used by any thread.
 */
public final class SnapshotSet2 implements AutoCloseable {

    // The order of the k-nearest result of a partition: by distance from the point and then by ID.
    private static Comparator<Entity2> nearestFirst(Point2 point) {
        return Comparator.<Entity2>comparingDouble(entity -> entity.position().distanceSquared(point))
                .thenComparingLong(Entity2::id);
    }

    private final long tick;
    private final List<AcquiredSnapshot2> snapshots;
    private final List<Failure> failures;

    private SnapshotSet2(long tick, List<AcquiredSnapshot2> snapshots, List<Failure> failures) {
        this.tick = tick;
        this.snapshots = Collections.unmodifiableList(snapshots);
        this.failures = Collections.unmodifiableList(failures);
    }

    /**
     * Acquires the snapshot of the given tick on each partition with the default timeout, like
     * {@link #acquire(List, long, AcquireOptions)}.
     */
    public static SnapshotSet2 acquire(List<Partition2> partitions, long tick) {
        return acquire(partitions, tick, AcquireOptions.defaultTimeout());
    }

    /**
     * Acquires the snapshot of the given tick on each partition, in the order of the list, like
     * {@link Partition2#acquire(long, AcquireOptions)}. A partition whose snapshot is not
     * available is a failure of the set and does not stop the others: the snapshot is not
     * published yet, is out of the retention window, is before the creation or at the removal
     * of the partition or after it, or the partition is destroyed. Any other error stops the
     * call: the set releases the snapshots acquired before and throws the error. An empty list
     * gives an empty, complete set.
     *
     * @param partitions the partitions, each one only once
     * @param tick the tick of the snapshots
     * @param options the timeout of each acquisition
     * @return the acquired snapshots and the failures
     * @throws NullPointerException if partitions, one of its elements or options is null
     * @throws IllegalArgumentException if tick is negative or a partition is in the list more
     *     than once
     */
    public static SnapshotSet2 acquire(List<Partition2> partitions, long tick, AcquireOptions options) {
        Objects.requireNonNull(partitions);
        Objects.requireNonNull(options);
        RetentionWindow.checkAcquiredTick(tick);
        // The handles have no equals: the space makes one handle for each partition.
        Set<Partition2> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Partition2 partition : partitions) {
            if (!seen.add(Objects.requireNonNull(partition))) {
                throw new IllegalArgumentException("Partition in the list more than once: " + partition.name());
            }
        }
        List<AcquiredSnapshot2> snapshots = new ArrayList<>(partitions.size());
        List<Failure> failures = new ArrayList<>();
        try {
            for (Partition2 partition : partitions) {
                try {
                    snapshots.add(partition.acquire(tick, options));
                } catch (SnapshotNotAvailableException | UnknownPartitionException e) {
                    failures.add(new Failure(partition, e));
                }
            }
        } catch (Throwable e) {
            releaseAll(snapshots, e);
            throw e;
        }
        return new SnapshotSet2(tick, snapshots, failures);
    }

    /** Returns the tick of the snapshots, the one asked for. */
    public long tick() {
        return tick;
    }

    /** Returns the acquired snapshots, in the order of the partitions. */
    public List<AcquiredSnapshot2> snapshots() {
        return snapshots;
    }

    /** Returns the partitions whose snapshot is not available, in the order of the partitions. */
    public List<Failure> failures() {
        return failures;
    }

    /** Returns true if the snapshot of every partition is acquired. */
    public boolean isComplete() {
        return failures.isEmpty();
    }

    /**
     * Returns the entities of the acquired snapshots that are inside the region or on its border.
     * The order of the result is not defined.
     *
     * @param region the region of the query
     * @return the entities of all the acquired snapshots in the region
     * @throws IllegalStateException if an acquisition is released or expired
     */
    public List<Entity2> findInRegion(Region2 region) {
        Objects.requireNonNull(region);
        List<Entity2> result = new ArrayList<>();
        for (AcquiredSnapshot2 snapshot : snapshots) {
            result.addAll(snapshot.findInRegion(region));
        }
        return result;
    }

    /**
     * Returns the entities of the acquired snapshots nearest to a point, at most count of them,
     * sorted by distance from the point and then by ID, like the query on a single snapshot.
     * Each snapshot gives its count nearest entities, and the result keeps the count nearest of
     * all of them.
     *
     * @param point the point of the query
     * @param count the greatest number of entities in the result
     * @return the nearest entities of all the acquired snapshots
     * @throws IllegalArgumentException if count is negative
     * @throws IllegalStateException if an acquisition is released or expired
     */
    public List<Entity2> findNearest(Point2 point, int count) {
        Objects.requireNonNull(point);
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        List<Entity2> result = new ArrayList<>();
        for (AcquiredSnapshot2 snapshot : snapshots) {
            result.addAll(snapshot.findNearest(point, count));
        }
        result.sort(nearestFirst(point));
        return result.size() <= count ? result : new ArrayList<>(result.subList(0, count));
    }

    /**
     * Releases all the acquisitions. It goes on also if a release fails, and then throws the
     * first error. Calling it again does nothing.
     */
    @Override
    public void close() {
        RuntimeException error = null;
        for (AcquiredSnapshot2 snapshot : snapshots) {
            try {
                snapshot.close();
            } catch (RuntimeException e) {
                if (error == null) {
                    error = e;
                } else {
                    error.addSuppressed(e);
                }
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /** Releases the snapshots after an error, which keeps the errors of the releases. */
    private static void releaseAll(List<AcquiredSnapshot2> snapshots, Throwable error) {
        for (AcquiredSnapshot2 snapshot : snapshots) {
            try {
                snapshot.close();
            } catch (RuntimeException e) {
                error.addSuppressed(e);
            }
        }
    }

    /**
     * A partition whose snapshot is not available.
     *
     * @param partition the partition
     * @param error why the snapshot is not available: a SnapshotNotAvailableException, or an
     *     UnknownPartitionException if the partition is destroyed
     */
    public record Failure(Partition2 partition, RuntimeException error) {

        /** Fails with NullPointerException if partition or error is null. */
        public Failure {
            Objects.requireNonNull(partition);
            Objects.requireNonNull(error);
        }

        /**
         * Returns the reason of the SnapshotNotAvailableException, or empty if the partition is
         * destroyed.
         */
        public Optional<Reason> reason() {
            return error instanceof SnapshotNotAvailableException e ? Optional.of(e.reason()) : Optional.empty();
        }
    }
}
