package badspace.service.partition;

import badspace.service.partition.snapshot.MonotonicClock;

/**
 * A clock for the tests that runs an action at a chosen reading, before it returns the time:
 * the test puts an event of another part of the node between two steps of an operation that
 * read the clock, without sleeps or other threads. Only the test thread uses it.
 */
final class SteppingClock implements MonotonicClock {

    private long nanos;
    private int readings;
    private int actionReading;
    private Runnable action;

    @Override
    public long nanoTime() {
        readings++;
        if (action != null && readings == actionReading) {
            // Cleared first, so the readings during the action do not run it again.
            Runnable current = action;
            action = null;
            current.run();
        }
        return nanos;
    }

    /** Sets the time that the next readings return. */
    void set(long nanos) {
        this.nanos = nanos;
    }

    /** Runs the action at the given reading from now, 1 for the next one, before it returns the time. */
    void atReading(int reading, Runnable action) {
        this.actionReading = readings + reading;
        this.action = action;
    }
}
