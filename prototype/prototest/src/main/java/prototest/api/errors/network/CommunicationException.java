package prototest.api.errors.network;

/** Base class for remote communication errors. */
public abstract class CommunicationException extends RuntimeException {
    public CommunicationException(String message) {
        super(message);
    }
}
