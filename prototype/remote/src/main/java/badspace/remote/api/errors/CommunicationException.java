package badspace.remote.api.errors;

/**
 * Base class for remote communication errors. A {@link RetryableCommunicationException}
 * allows another attempt with the same request ID. A {@link NodeRecoveryRequiredException}
 * requires recovery before new calls through the affected proxy.
 */
public abstract class CommunicationException extends RuntimeException {
    /**
     * Creates a communication error with a detail message.
     *
     * @param message the detail message, or {@code null} if none
     */
    public CommunicationException(String message) {
        super(message);
    }
}
