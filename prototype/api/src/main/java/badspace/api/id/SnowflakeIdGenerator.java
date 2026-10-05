package badspace.api.id;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Generates unique 64-bit entity IDs in Snowflake style.
 * There must be one generator per API process, shared by all spaces:
 * two generators with the same generator ID can create the same IDs.
 *
 * <p>Layout of an ID, from the highest bit:
 * <pre>
 *  1 bit  | 41 bit                    | 10 bit       | 12 bit
 *  sign 0 | milliseconds since EPOCH  | generator ID | sequence
 * </pre>
 *
 * <p>The generator uses a logical time that never goes back: the maximum between
 * the last timestamp used and the clock. When the sequence of a millisecond is used up,
 * it moves to the next millisecond without waiting for the clock. The logical time can be
 * ahead of the clock by at most {@code maxDriftMillis}; beyond that, {@link #nextId()} fails.
 * So {@link #nextId()} never blocks.
 *
 * <p>Thread-safe.
 */
public final class SnowflakeIdGenerator {

    /** Start of the timestamp field: 2026-01-01T00:00:00Z. Never change it. */
    public static final long EPOCH_MILLIS = 1_767_225_600_000L;

    public static final int TIMESTAMP_BITS = 41;
    public static final int GENERATOR_BITS = 10;
    public static final int SEQUENCE_BITS = 12;

    public static final long MAX_GENERATOR_ID = (1L << GENERATOR_BITS) - 1;
    static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    static final long MAX_TIMESTAMP = (1L << TIMESTAMP_BITS) - 1;

    /** Default limit on how far the logical time can be ahead of the clock. */
    public static final long DEFAULT_MAX_DRIFT_MILLIS = 1000;

    private final long generatorId;
    private final long maxDriftMillis;
    private final LongSupplier clock;

    // Guarded by this. The timestamp is relative to EPOCH_MILLIS.
    private long lastTimestamp = -1;
    private long sequence;

    /** Creates a generator with the default drift limit. See {@link #SnowflakeIdGenerator(long, long)}. */
    public SnowflakeIdGenerator(long generatorId) {
        this(generatorId, DEFAULT_MAX_DRIFT_MILLIS);
    }

    /**
     * Creates a generator. The constructor waits {@code maxDriftMillis} before it returns:
     * a previous generator with the same ID may have used timestamps up to that much
     * ahead of the clock, and the new one must not use them again.
     *
     * @param generatorId from 0 to {@link #MAX_GENERATOR_ID}; the layer that manages
     *                    the instances assigns it
     * @param maxDriftMillis how far the logical time can be ahead of the clock
     */
    public SnowflakeIdGenerator(long generatorId, long maxDriftMillis) {
        this(generatorId, maxDriftMillis, System::currentTimeMillis, SnowflakeIdGenerator::sleep);
    }

    SnowflakeIdGenerator(long generatorId, long maxDriftMillis, LongSupplier clock, LongConsumer sleeper) {
        if (generatorId < 0 || generatorId > MAX_GENERATOR_ID) {
            throw new IllegalArgumentException(
                    "Generator ID must be between 0 and " + MAX_GENERATOR_ID + ": " + generatorId);
        }
        if (maxDriftMillis < 0) {
            throw new IllegalArgumentException("Max drift must not be negative: " + maxDriftMillis);
        }
        this.generatorId = generatorId;
        this.maxDriftMillis = maxDriftMillis;
        this.clock = clock;
        sleeper.accept(maxDriftMillis);
        checkTimestamp(now());
    }

    /**
     * Returns a new ID, greater than all the IDs this generator returned before.
     *
     * @throws IllegalStateException if the logical time would be too far ahead of the clock,
     *                               or if the timestamp field is used up
     */
    public synchronized long nextId() {
        long now = now();
        long timestamp;
        long seq;
        if (now > lastTimestamp) {
            timestamp = now;
            seq = 0;
        } else if (sequence < MAX_SEQUENCE) {
            // Same millisecond, or the clock went back: stay on the last timestamp
            timestamp = lastTimestamp;
            seq = sequence + 1;
        } else {
            // Sequence used up: borrow the next millisecond
            timestamp = lastTimestamp + 1;
            seq = 0;
        }
        if (timestamp - now > maxDriftMillis) {
            throw new IllegalStateException("Logical time is more than " + maxDriftMillis
                    + " ms ahead of the clock: too many IDs, or the clock went back");
        }
        checkTimestamp(timestamp);
        lastTimestamp = timestamp;
        sequence = seq;
        return timestamp << (GENERATOR_BITS + SEQUENCE_BITS) | generatorId << SEQUENCE_BITS | seq;
    }

    /** Returns the timestamp field of an ID, in milliseconds since the Unix epoch. */
    public static long timestampMillis(long id) {
        return (id >>> (GENERATOR_BITS + SEQUENCE_BITS)) + EPOCH_MILLIS;
    }

    /** Returns the generator ID field of an ID. */
    public static long generatorId(long id) {
        return (id >>> SEQUENCE_BITS) & MAX_GENERATOR_ID;
    }

    /** Returns the sequence field of an ID. */
    public static long sequence(long id) {
        return id & MAX_SEQUENCE;
    }

    private long now() {
        return clock.getAsLong() - EPOCH_MILLIS;
    }

    private static void checkTimestamp(long timestamp) {
        if (timestamp < 0 || timestamp > MAX_TIMESTAMP) {
            throw new IllegalStateException("Timestamp is outside the range of the ID: " + timestamp);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to start", e);
        }
    }
}
