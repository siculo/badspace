package badspace.common.partition;

import java.util.Objects;

/**
 * Thrown when a reader asks for the snapshot of a tick that it cannot acquire. The
 * {@linkplain #reason() reason} says why. No acquisition is made.
 */
public final class SnapshotNotAvailableException extends RuntimeException {

    /** Why the snapshot of a tick cannot be acquired. */
    public enum Reason {
        /** The tick is not before the tick of the partition: its snapshot is not published yet. */
        NOT_YET_PUBLISHED,
        /** The tick is older than the retention window. */
        OUT_OF_RETENTION,
        /** The partition did not exist yet at the tick. */
        BEFORE_CREATION
    }

    private final Reason reason;

    /**
     * Creates the exception with its reason and a message that says which snapshot was asked
     * for. Fails with NullPointerException if reason is null.
     */
    public SnapshotNotAvailableException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }

    /** Returns why the snapshot cannot be acquired. */
    public Reason reason() {
        return reason;
    }
}
