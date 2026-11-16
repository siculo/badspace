package prototest.api.errors.network;

/**
 * A final communication failure that requires recovery before new calls. The proxy
 * marks the affected session as failed and rejects new calls until it is recovered.
 * Other pending calls receive {@link ConnectionUnavailableException}, with this
 * failure as their cause, without waiting for the transport to finish.
 *
 * <p>This error does not prove that the operation had no effect on the server.
 * Recovering the proxy clears only its local error state; it does not restore the node.
 */
public class NodeRecoveryRequiredException extends CommunicationException {
    /**
     * Creates a final communication error with a detail message.
     *
     * @param message the detail message, or {@code null} if none
     */
    public NodeRecoveryRequiredException(String message) {
        super(message);
    }

    /**
     * Creates a final communication error with a detail message and cause.
     *
     * @param message the detail message, or {@code null} if none
     * @param cause the underlying failure, or {@code null} if unknown
     */
    public NodeRecoveryRequiredException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
