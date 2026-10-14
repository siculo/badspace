package badspace.service.partition;

/**
 * The monotonic clock of the node, which measures the timeouts of the acquisitions.
 * Only the difference between two readings has a meaning. The tests replace it with
 * a clock that moves only when they say.
 */
interface MonotonicClock {

    /** The clock of the system. */
    MonotonicClock SYSTEM = System::nanoTime;

    /** Returns the time in nanoseconds. Any thread can call it. */
    long nanoTime();
}
