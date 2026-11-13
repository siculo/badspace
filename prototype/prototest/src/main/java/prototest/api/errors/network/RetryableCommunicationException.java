package prototest.api.errors.network;

/** A failed transport attempt that can be retried with the same request ID. */
public class RetryableCommunicationException extends CommunicationException {
    public RetryableCommunicationException(String message) {
        super(message);
    }
}
