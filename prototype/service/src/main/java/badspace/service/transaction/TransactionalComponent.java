package badspace.service.transaction;

/**
 * A structure of a partition that takes part in its commits: the slots, the spatial index and
 * the metadata. The structure has a working state, which the writer changes with methods of
 * its own, and makes immutable snapshots of it. The partition storage coordinates the
 * structures: it gives them the commit sequence and keeps the snapshot of the last commit,
 * which is the base for {@link #restore}. The structures do not know the tick or the owner of
 * the partition.
 * <p>
 * The storage restores the structures in a fixed order: slots, indices, metadata. An index
 * reads the slots of the writer, so it is restored after them.
 * <p>
 * Only the writer uses a structure, while it holds the lock of the partition; the snapshots
 * never change, so other threads can read them.
 *
 * @param <S> the type of the snapshots of the structure
 */
public interface TransactionalComponent<S> {

    /**
     * Returns a complete and immutable snapshot of the working state, for the commit with
     * the given internal sequence. The next writes do not change the snapshot. The preparation
     * can change internal details of the working state, for example to make the nodes of a
     * copy-on-write structure read-only.
     *
     * @param commitSequence the internal sequence of the commit, greater than the one of
     *     the last commit; it is not a logical tick
     * @return the snapshot of the working state
     */
    S prepareCommit(long commitSequence);

    /**
     * Brings the working state back to the content of a snapshot: the writes after it are
     * lost. The snapshot does not change. The physical layout can be different, but the
     * content is the same, and the working state can take new writes and new commits. It
     * can be called again with the same snapshot.
     *
     * @param confirmed the snapshot of the last commit, or the empty snapshot of the
     *     structure if there was no commit
     * @throws IllegalArgumentException if the snapshot does not come from this kind of
     *     structure
     */
    void restore(S confirmed);
}
