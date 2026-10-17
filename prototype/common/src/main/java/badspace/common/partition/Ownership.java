package badspace.common.partition;

/**
 * The state of the ownership of a partition. A partition has at most one owner, its
 * writer. The generation grows by one at each change: when someone takes the ownership,
 * passes it to another writer or takes it away from the owner, and when the owner
 * releases it. So a generation names one state of the ownership. A new partition has
 * no owner, at generation 0.
 *
 * @param generation the generation of the state, 0 or more
 * @param owned true if the partition has an owner, the one with the {@link WriteToken}
 *        of this generation
 */
public record Ownership(long generation, boolean owned) {

    /** The state of a new partition: no owner, at generation 0. */
    public static final Ownership INITIAL = new Ownership(0, false);

    /** Fails with IllegalArgumentException if generation is negative. */
    public Ownership {
        if (generation < 0) {
            throw new IllegalArgumentException("Negative generation: " + generation);
        }
    }
}
