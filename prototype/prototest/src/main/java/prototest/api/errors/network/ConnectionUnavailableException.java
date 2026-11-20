package prototest.api.errors.network;

/**
 * A call cannot continue because its proxy has no usable session. Pending calls
 * receive this error without waiting for the transport to finish.
 *
 * <p>The cause is the original error that made the session fail. As a
 * {@link NodeRecoveryRequiredException}, this error can be handled together with
 * other failures that require recovery.
 */
public final class ConnectionUnavailableException extends NodeRecoveryRequiredException {
    /**
     * Creates an error for a call in a failed proxy session.
     *
     * @param cause the error that made the session fail, or {@code null} if unknown
     */
    public ConnectionUnavailableException(NodeRecoveryRequiredException cause) {
        this("Connection unavailable: proxy recovery required", cause);
    }

    /**
     * Creates an error describing why the proxy has no usable session.
     * @param message the reason the call cannot continue
     * @param cause the original failure, if any
     */
    public ConnectionUnavailableException(String message, NodeRecoveryRequiredException cause) {
        super(message, cause);
    }
}
