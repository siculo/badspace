package prototest.example.api.errors.service;

/**
 * Base class for service errors. The proxy passes these errors to the caller
 * without retrying the operation or marking its session as failed.
 * The proxy remains usable unless a concurrent communication failure requires recovery.
 */
public abstract class ServiceException extends RuntimeException {
    /**
     * Creates a service error with a detail message.
     *
     * @param message the detail message, or {@code null} if none
     */
    public ServiceException(String message) {
        super(message);
    }
}
