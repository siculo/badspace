package badspace.service.partition;

import badspace.common.partition.AcquireOptions;

/**
 * An acquisition as the storage keeps it: the acquired snapshot, its tick and its timeout.
 * The entry decides if the acquisition is expired; the storage only removes the
 * expired entries. It never changes, so any thread can read it.
 *
 * @param snapshot the acquired snapshot
 * @param tick the acquired tick: the snapshot can be valid also for other ticks
 * @param acquiredAtNanos the time of the acquisition on the clock of the node
 * @param timeoutNanos the timeout
 */
record AcquisitionEntry2(StorageSnapshot2 snapshot, long tick, long acquiredAtNanos, long timeoutNanos) {

    /** Returns the entry of an acquisition made now with the given options. */
    static AcquisitionEntry2 of(StorageSnapshot2 snapshot, long tick, AcquireOptions options, long nowNanos) {
        return new AcquisitionEntry2(snapshot, tick, nowNanos, options.timeoutNanos());
    }

    /** Returns true if the timeout has passed at the given time. */
    boolean isExpired(long nowNanos) {
        // The difference does not overflow, unlike a deadline computed as a sum.
        return nowNanos - acquiredAtNanos >= timeoutNanos;
    }
}
