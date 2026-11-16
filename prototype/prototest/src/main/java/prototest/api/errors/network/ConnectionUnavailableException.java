package prototest.api.errors.network;

/**
 * A call cannot continue because its proxy session has failed. Pending calls receive
 * this error without waiting for the transport to finish. New calls are rejected
 * before invoking the network, until the proxy is recovered.
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
        super("Connection unavailable: proxy recovery required", cause);
    }
}
