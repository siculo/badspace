package badspace.remote.api.errors;

/**
 * A call cannot continue because its proxy has no usable session. Pending calls
 * receive this error without waiting for the transport to finish.
 *
 * <p>The cause is the original session failure when available. It can be
 * {@code null}, for example while the proxy is disconnected or changing its
 * connection.
 *
 * <p>After a session failure, use {@code recover()}. Before the first connection
 * or after a confirmed disconnection, use {@code connect()}. Wait for any
 * connection change already in progress. If disconnection failed, retry
 * {@code disconnect()} before connecting again.
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
