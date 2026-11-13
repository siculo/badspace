package prototest.api.errors.network;

/** A final communication failure that requires recovery before new calls. */
public class NodeRecoveryRequiredException extends CommunicationException {
    public NodeRecoveryRequiredException(String message) {
        super(message);
    }

    public NodeRecoveryRequiredException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
