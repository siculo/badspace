package badspace.service.partition;

import badspace.common.partition.AcquireOptions;

/**
 * An acquisition as the storage keeps it: the acquired snapshot and its timeout.
 * The entry decides if the acquisition is expired; the storage only removes the
 * expired entries. It never changes, so any thread can read it.
 *
 * @param snapshot the acquired snapshot
 * @param acquiredAtNanos the time of the acquisition on the clock of the node
 * @param timeoutNanos the timeout
 */
record AcquisitionEntry3(StorageSnapshot3 snapshot, long acquiredAtNanos, long timeoutNanos) {

    /** Returns the entry of an acquisition made now with the given options. */
    static AcquisitionEntry3 of(StorageSnapshot3 snapshot, AcquireOptions options, long nowNanos) {
        return new AcquisitionEntry3(snapshot, nowNanos, options.timeoutNanos());
    }

    /** Returns true if the timeout has passed at the given time. */
    boolean isExpired(long nowNanos) {
        // The difference does not overflow, unlike a deadline computed as a sum.
        return nowNanos - acquiredAtNanos >= timeoutNanos;
    }
}
