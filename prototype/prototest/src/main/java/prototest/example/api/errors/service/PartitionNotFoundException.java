package prototest.example.api.errors.service;

/** The requested partition is not present on the node. */
public class PartitionNotFoundException extends ServiceException {
    /**
     * Creates an error for a partition that is not present on the node.
     *
     * @param message the detail message, or {@code null} if none
     */
    public PartitionNotFoundException(String message) {
        super(message);
    }
}
