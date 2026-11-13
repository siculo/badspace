package prototest.api.errors.service;

/** The requested partition is not present on the node. */
public class PartitionNotFoundException extends ServiceException {
    public PartitionNotFoundException(String message) {
        super(message);
    }
}
