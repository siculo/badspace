package prototest.api.errors.network;

/**
 * A failed transport attempt that can be retried with the same request ID.
 * The proxy retries within its retry timeout. If that timeout is reached after
 * a failed attempt, it reports a {@link NodeRecoveryRequiredException} with this
 * error as the cause and marks the session as failed.
 */
public class RetryableCommunicationException extends CommunicationException {
    /**
     * Creates a retryable communication error with a detail message.
     *
     * @param message the detail message, or {@code null} if none
     */
    public RetryableCommunicationException(String message) {
        super(message);
    }
}
