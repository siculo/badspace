package badspace.common.partition;

import java.time.Duration;
import java.util.Objects;

/**
 * The options of an acquisition: its optional timeout. The timeout is measured from
 * the moment of the acquisition with the monotonic clock of the node. After the
 * timeout the acquisition is expired: it behaves like a released one, and it is
 * never renewed. Without a timeout only the release ends the acquisition.
 *
 * @param timeoutNanos the timeout in nanoseconds, positive, or {@link #NO_TIMEOUT}
 */
public record AcquireOptions(long timeoutNanos) {

    /** The value of timeoutNanos for an acquisition without timeout. */
    public static final long NO_TIMEOUT = Long.MAX_VALUE;

    private static final AcquireOptions NO_TIMEOUT_OPTIONS = new AcquireOptions(NO_TIMEOUT);
    // The greatest timeout that the clock can measure: a greater one is the same as no timeout.
    private static final Duration MAX_TIMEOUT = Duration.ofNanos(NO_TIMEOUT);

    /** Fails with IllegalArgumentException if timeoutNanos is zero or negative. */
    public AcquireOptions {
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("Timeout is not positive: " + timeoutNanos + " ns");
        }
    }

    /** Returns the options of an acquisition without timeout: the default. */
    public static AcquireOptions noTimeout() {
        return NO_TIMEOUT_OPTIONS;
    }

    /**
     * Returns the options of an acquisition that expires after the timeout. A timeout
     * too large for the clock, about 292 years or more, is the same as no timeout.
     * Fails with IllegalArgumentException if the timeout is zero or negative.
     */
    public static AcquireOptions timeout(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout is not positive: " + timeout);
        }
        if (timeout.compareTo(MAX_TIMEOUT) >= 0) {
            return NO_TIMEOUT_OPTIONS;
        }
        return new AcquireOptions(timeout.toNanos());
    }

    /** Returns true if the acquisition expires after a timeout. */
    public boolean hasTimeout() {
        return timeoutNanos != NO_TIMEOUT;
    }
}
