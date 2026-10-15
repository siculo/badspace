package badspace.common.partition;

/**
 * Thrown when a reader asks for a snapshot that it cannot acquire: the snapshot
 * is outside the retention window, its commit was never published, or no
 * commit matches the request. No acquisition is made.
 */
public final class SnapshotNotAvailableException extends RuntimeException {

    /** Creates the exception with a message that says which snapshot was asked for. */
    public SnapshotNotAvailableException(String message) {
        super(message);
    }
}
