package badspace.common.partition;

/**
 * Thrown when a write, a commit or a removal of a partition comes with a {@link WriteToken}
 * that is not the current one: the ownership changed after the token was given, so its
 * holder is not the owner any more. The partition does not change. The token never becomes
 * current again: its holder can read the {@link Ownership} and, if the software allows it,
 * claim the partition again.
 */
public final class OwnershipChangedException extends RuntimeException {

    /** Creates the exception with a message that says which token was not current. */
    public OwnershipChangedException(String message) {
        super(message);
    }
}
