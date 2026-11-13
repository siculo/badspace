package prototest.api.errors.network;

public abstract class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
