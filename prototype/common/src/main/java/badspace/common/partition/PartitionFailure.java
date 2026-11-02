package badspace.common.partition;

import java.util.Objects;

/**
 * The report of a failure of a partition: an exception thrown while the partition was
 * changing the state of the writer, after the checks of the input. A failure is not a refusal:
 * a refused operation, for example with input that is not valid or with a token that is not
 * current, changes nothing and has no report.
 * <p>
 * The partition is the one of the operation, or the one whose health gives the report.
 *
 * @param operation the operation that failed
 * @param component the structure where the failure happened, or {@link PartitionComponent#UNKNOWN}
 * @param outcome whether the writes not committed are discarded or the partition needs a recovery
 * @param baseTick the tick of the last commit, the state that the partition keeps; for a
 *        partition without commits, its creation tick. More commits in the same tick have the
 *        same base tick.
 * @param cause the failure of the operation; for a claim or a release, the failure of the
 *        restore that they do
 * @param restoreFailure the failure of the restore after the failure of a write or a commit,
 *        or null if the restore worked or the operation is a claim or a release
 */
public record PartitionFailure(PartitionOperation operation, PartitionComponent component, FailureOutcome outcome,
        long baseTick, Throwable cause, Throwable restoreFailure) {

    /** Fails with NullPointerException if a value other than restoreFailure is null. */
    public PartitionFailure {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(component);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(cause);
    }
}
