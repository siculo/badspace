package badspace.service.partition;

import java.time.Duration;

/** A clock that moves only when the test says. The start is near the overflow of the value. */
final class FakeClock implements MonotonicClock {

    // Written by the test thread, read by the threads of the queries.
    private volatile long nanos = Long.MAX_VALUE - 1_000;

    @Override
    public long nanoTime() {
        return nanos;
    }

    void advance(Duration duration) {
        nanos += duration.toNanos();
    }
}
