package badspace.common.partition;

/**
 * Thrown when a write or a commit fails after the checks of the input: the cause is the
 * original exception, and {@link #failure()} says what the partition did. If the outcome is
 * {@link FailureOutcome#WRITES_DISCARDED}, the writes not committed are discarded, also the ones
 * of the ticks before, and the writer can go on. If it is
 * {@link FailureOutcome#RECOVERY_REQUIRED}, the partition refuses writes and commits until a
 * rollback or a change of the ownership restores it.
 * <p>
 * Only exceptions become a PartitionFailureException: an {@link Error}, for example when the
 * memory ends, goes to the caller as it is, with the failure of the restore as a suppressed
 * exception, and the health of the partition says what it left.
 */
public final class PartitionFailureException extends RuntimeException {

    private final transient PartitionFailure failure;

    /** Creates the exception from the report of the failure, with its cause. */
    public PartitionFailureException(PartitionFailure failure) {
        super("Failure of " + failure.operation() + " in " + failure.component() + ": " + failure.outcome(),
                failure.cause());
        this.failure = failure;
    }

    /** Returns the report of the failure. */
    public PartitionFailure failure() {
        return failure;
    }
}
