package badspace.common.partition;

/**
 * Thrown when a reader asks for a version that it cannot acquire: the version
 * is outside the retention window, its commit was never published, or no
 * commit matches the request. No acquisition is made.
 */
public final class VersionNotAvailableException extends RuntimeException {

    /** Creates the exception with a message that says which version was asked for. */
    public VersionNotAvailableException(String message) {
        super(message);
    }
}
