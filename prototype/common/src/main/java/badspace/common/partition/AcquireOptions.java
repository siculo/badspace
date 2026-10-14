package badspace.common.partition;

import java.time.Duration;
import java.util.Objects;

/**
 * The options of an acquisition: its timeout. Every acquisition has a timeout, so a
 * reader that dies without releasing cannot keep a version in memory for ever. The
 * timeout is measured from the moment of the acquisition with the monotonic clock of
 * the node. After the timeout the acquisition is expired: it behaves like a released
 * one, and it is never renewed.
 *
 * @param timeoutNanos the timeout in nanoseconds, positive and not greater than
 *     {@link #MAX_TIMEOUT}
 */
public record AcquireOptions(long timeoutNanos) {

    /** The timeout of an acquisition without explicit options. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);
    /**
     * The greatest timeout. For now it is a constant; a reader that needs a version for
     * a longer time must make a copy of it.
     */
    public static final Duration MAX_TIMEOUT = Duration.ofHours(1);

    private static final AcquireOptions DEFAULT_OPTIONS = new AcquireOptions(DEFAULT_TIMEOUT.toNanos());

    /**
     * Fails with IllegalArgumentException if timeoutNanos is zero, negative or greater
     * than {@link #MAX_TIMEOUT}.
     */
    public AcquireOptions {
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("Timeout is not positive: " + timeoutNanos + " ns");
        }
        if (timeoutNanos > MAX_TIMEOUT.toNanos()) {
            throw new IllegalArgumentException(
                    "Timeout is greater than " + MAX_TIMEOUT + ": " + timeoutNanos + " ns");
        }
    }

    /** Returns the options with the {@link #DEFAULT_TIMEOUT default timeout}. */
    public static AcquireOptions defaultTimeout() {
        return DEFAULT_OPTIONS;
    }

    /**
     * Returns the options of an acquisition that expires after the timeout. Fails with
     * IllegalArgumentException if the timeout is zero, negative or greater than
     * {@link #MAX_TIMEOUT}.
     */
    public static AcquireOptions timeout(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout is not positive: " + timeout);
        }
        // Checked before the conversion, which overflows for very large durations.
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Timeout is greater than " + MAX_TIMEOUT + ": " + timeout);
        }
        return new AcquireOptions(timeout.toNanos());
    }
}
