package badspace.common.partition;

import java.util.Objects;

/**
 * The name of a partition: its public identifier, which the software chooses at the creation.
 * The same name means the same partition also across different runs, and the saves of a
 * partition are identified by its name; the {@link PartitionId} is only valid while the
 * partition runs on a node. The name becomes the name of a directory, so it has the
 * characters {@code a-z}, {@code 0-9}, {@code -}, {@code _} and {@code .}, only lower case
 * because many file systems do not tell upper case from lower case, and from 1 to
 * {@link #MAX_LENGTH} characters; it is neither {@code .} nor {@code ..}.
 *
 * @param value the name
 */
public record PartitionName(String value) {

    /** The greatest number of characters of a name. */
    public static final int MAX_LENGTH = 128;

    /**
     * Fails with NullPointerException if value is null, and with IllegalArgumentException if
     * it does not follow the rules of a name.
     */
    public PartitionName {
        Objects.requireNonNull(value);
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Partition name must have from 1 to " + MAX_LENGTH
                    + " characters: \"" + value + "\"");
        }
        if (value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Partition name cannot be \"" + value + "\"");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                throw new IllegalArgumentException("Partition name can have only a-z, 0-9, '-', '_' and '.': \""
                        + value + "\"");
            }
        }
    }

    /** Returns the name with the given value; fails like the constructor. */
    public static PartitionName partitionName(String value) {
        return new PartitionName(value);
    }
}
