package prototest.api.errors.network;

/** A call cannot continue because its proxy session has failed. */
public final class ConnectionUnavailableException extends NodeRecoveryRequiredException {
    public ConnectionUnavailableException(NodeRecoveryRequiredException cause) {
        super("Connection unavailable: proxy recovery required", cause);
    }
}
