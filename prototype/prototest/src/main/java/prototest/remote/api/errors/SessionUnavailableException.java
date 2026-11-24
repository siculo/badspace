package prototest.remote.api.errors;

/** The server rejected the operation before execution because its session was unavailable. */
public final class SessionUnavailableException extends NodeRecoveryRequiredException {
    /** @param message the reason the session is unavailable */
    public SessionUnavailableException(String message) {
        super(message);
    }
}
