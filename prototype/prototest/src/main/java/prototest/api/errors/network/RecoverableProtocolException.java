package prototest.api.errors.network;

public class RecoverableProtocolException extends ProtocolException {
    public RecoverableProtocolException(String message) {
        super(message);
    }
}
