package badspace.service.transaction;

/**
 * A structure of a partition that takes part in its commits: the slots, the spatial index and
 * the metadata. The structure has a working state, which the writer changes with methods of
 * its own, and makes immutable snapshots of it. The partition storage coordinates the
 * structures: it gives them the commit sequence and keeps the snapshot of the last commit,
 * which is the base for {@link #restore}. The structures do not know the tick or the owner of
 * the partition.
 * <p>
 * A commit has two steps. The storage first prepares the snapshots of all the structures; if
 * a preparation fails, it abandons the snapshots already prepared, and the last commit stays
 * the base of each structure. Only when all the snapshots are ready it accepts them: the
 * acceptance cannot fail, so either all the structures accept the commit or none does.
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
     * copy-on-write structure read-only, but it does not replace the base of the structure:
     * the snapshot becomes the base only with {@link #acceptCommit}. A sequence is never used
     * twice, also when its preparation is abandoned.
     *
     * @param commitSequence the internal sequence of the commit, greater than the one of
     *     the last commit; it is not a logical tick
     * @return the snapshot of the working state
     */
    S prepareCommit(long commitSequence);

    /**
     * Makes the snapshot the base of the structure: the next commit starts from it. It is a
     * small step, with no allocations and no checks that can fail.
     *
     * @param prepared the snapshot of the last preparation, with no writes after it
     */
    void acceptCommit(S prepared);

    /**
     * Gives back the resources of a snapshot that the storage does not accept, without
     * freeing the data that it shares with other snapshots. The base of the structure and the
     * working state do not change, and the writer can go on and commit again.
     *
     * @param prepared the snapshot of the last preparation, which nobody reads
     */
    void abandonCommit(S prepared);

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
