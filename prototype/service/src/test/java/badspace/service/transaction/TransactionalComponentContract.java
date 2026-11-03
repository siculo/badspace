package badspace.service.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that each {@link TransactionalComponent} must pass. A subclass is the adapter of one
 * structure: it creates the structure, applies random writes to it and gives the content of
 * the working state and of a snapshot, as values that equals can compare. The comparisons are
 * on the content, not on the identity of the objects or on the physical layout.
 * <p>
 * The writes are random, with a fixed seed for each run, so a failure can be repeated. The
 * adapter must choose each write only from the random source and from the content: two
 * structures with the same content get the same write from two sources with the same seed.
 * The commit sequences start at 1 and grow at each commit, also after an abandoned preparation,
 * as in the partition storage. A commit is a preparation and its acceptance.
 *
 * @param <C> the type of the structure
 * @param <S> the type of its snapshots
 */
public abstract class TransactionalComponentContract<C extends TransactionalComponent<S>, S> {

    private static final int WRITES = 200;

    /** Returns a new structure, with no content. */
    protected abstract C newComponent();

    /** Returns the snapshot of a structure before its first commit. */
    protected abstract S emptySnapshot();

    /**
     * Applies to the structure a write chosen by the random source: an insertion, a change or
     * a removal.
     *
     * @param commit the commit sequence of the next commit, which confirms the write
     */
    protected abstract void write(C component, Random random, long commit);

    /** Returns the content of the working state. */
    protected abstract Object workingContent(C component);

    /** Returns the content of a snapshot. */
    protected abstract Object snapshotContent(S snapshot);

    /**
     * Fails if the internal structures of the working state are not consistent, for
     * example the auxiliary maps of an index. By default it checks nothing.
     */
    protected void checkStructure(C component) {
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void preparedSnapshotDoesNotChangeWithLaterWrites(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        writeMany(component, random, 1);
        S first = commit(component, 1);
        Object content = snapshotContent(first);
        assertEquals(workingContent(component), content);
        writeMany(component, random, 2);
        S second = commit(component, 2);
        Object secondContent = snapshotContent(second);
        writeMany(component, random, 3);
        component.abandonCommit(component.prepareCommit(3));
        writeMany(component, random, 4);
        assertEquals(content, snapshotContent(first));
        assertEquals(secondContent, snapshotContent(second));
    }

    /**
     * The restore shares data with the restored snapshot, which the writes after it must copy:
     * neither the restored snapshot nor the older ones change, also after an abandoned
     * preparation and a second restore.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void restoreAndLaterWritesDoNotChangeTheOlderSnapshots(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        writeMany(component, random, 1);
        S first = commit(component, 1);
        Object firstContent = snapshotContent(first);
        writeMany(component, random, 2);
        S second = commit(component, 2);
        Object secondContent = snapshotContent(second);
        writeMany(component, random, 3);
        component.abandonCommit(component.prepareCommit(3));
        component.restore(second);
        writeMany(component, random, 4);
        S third = commit(component, 4);
        Object thirdContent = snapshotContent(third);
        writeMany(component, random, 5);
        component.restore(third);
        writeMany(component, random, 5);
        commit(component, 5);
        writeMany(component, random, 6);
        assertEquals(firstContent, snapshotContent(first));
        assertEquals(secondContent, snapshotContent(second));
        assertEquals(thirdContent, snapshotContent(third));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void restoreBringsBackTheContentOfTheSnapshot(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        writeMany(component, random, 1);
        S confirmed = commit(component, 1);
        Object content = snapshotContent(confirmed);
        writeMany(component, random, 2);
        component.restore(confirmed);
        assertEquals(content, workingContent(component));
        assertEquals(content, snapshotContent(confirmed));
        checkStructure(component);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void restoreBeforeTheFirstCommitGoesBackToTheEmptySnapshot(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        writeMany(component, random, 1);
        S empty = emptySnapshot();
        component.restore(empty);
        assertEquals(snapshotContent(empty), workingContent(component));
        checkStructure(component);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void restoreWithoutWritesKeepsTheContent(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        writeMany(component, random, 1);
        S confirmed = commit(component, 1);
        component.restore(confirmed);
        assertEquals(snapshotContent(confirmed), workingContent(component));
        checkStructure(component);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void repeatedRestoreGivesTheSameContent(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        writeMany(component, random, 1);
        S confirmed = commit(component, 1);
        writeMany(component, random, 2);
        component.restore(confirmed);
        component.restore(confirmed);
        assertEquals(snapshotContent(confirmed), workingContent(component));
        checkStructure(component);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void commitWithoutWritesHasTheSameContent(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        S withoutWrites = commit(component, 1);
        assertEquals(snapshotContent(emptySnapshot()), snapshotContent(withoutWrites));
        writeMany(component, random, 2);
        S first = commit(component, 2);
        S second = commit(component, 3);
        assertEquals(snapshotContent(first), snapshotContent(second));
        writeMany(component, random, 4);
        component.restore(second);
        assertEquals(snapshotContent(first), workingContent(component));
    }

    /**
     * The structure that restores and the reference, which never restores, get the same
     * writes before the commit and after the restore: the commits after the restore, and the
     * working states, must have the same content.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void writesAfterRestoreMakeTheSameCommitsAsWithoutTheDiscardedWrites(long seed) {
        C tested = newComponent();
        C reference = newComponent();
        writeBoth(tested, reference, seed, 1);
        S confirmed = commit(tested, 1);
        commit(reference, 1);
        Object content = snapshotContent(confirmed);
        writeMany(tested, new Random(~seed), 2);
        tested.restore(confirmed);
        for (long sequence = 2; sequence <= 4; sequence++) {
            writeBoth(tested, reference, seed * 31 + sequence, sequence);
            checkStructure(tested);
            assertEquals(workingContent(reference), workingContent(tested), "working state before commit " + sequence);
            assertEquals(snapshotContent(commit(reference, sequence)), snapshotContent(commit(tested, sequence)),
                    "commit " + sequence);
        }
        assertEquals(content, snapshotContent(confirmed));
    }

    /**
     * A preparation that the storage abandons does not change the base: the restore goes back
     * to the last accepted snapshot, and the commits after it are the same as for a reference
     * that never prepared the abandoned commit. The commits after the restore have one write
     * each, so most of the state changed before the abandoned preparation does not change again.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void abandonedPreparationKeepsTheBase(long seed) {
        C tested = newComponent();
        C reference = newComponent();
        writeBoth(tested, reference, seed, 1);
        S confirmed = commit(tested, 1);
        commit(reference, 1);
        Object content = snapshotContent(confirmed);
        writeMany(tested, new Random(~seed), 2);
        tested.abandonCommit(tested.prepareCommit(2));
        tested.restore(confirmed);
        assertEquals(content, workingContent(tested));
        checkStructure(tested);
        for (long sequence = 3; sequence <= 5; sequence++) {
            write(tested, new Random(seed * 31 + sequence), sequence);
            write(reference, new Random(seed * 31 + sequence), sequence);
            assertEquals(snapshotContent(commit(reference, sequence)), snapshotContent(commit(tested, sequence)),
                    "commit " + sequence);
        }
        assertEquals(content, snapshotContent(confirmed));
    }

    /**
     * After an abandoned preparation the writer commits again, at once and then after more
     * writes: the commits have all the writes, also the ones before the abandoned preparation,
     * as for a reference that never prepared it, and the later commits do not change the
     * earlier snapshots.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void commitAfterAnAbandonedPreparationHasAllTheWrites(long seed) {
        C tested = newComponent();
        C reference = newComponent();
        writeBoth(tested, reference, seed, 1);
        commit(tested, 1);
        commit(reference, 1);
        writeBoth(tested, reference, seed + 1, 2);
        tested.abandonCommit(tested.prepareCommit(2));
        S retried = commit(tested, 3);
        Object retriedContent = snapshotContent(retried);
        assertEquals(snapshotContent(commit(reference, 3)), retriedContent);
        writeBoth(tested, reference, seed + 2, 4);
        S later = commit(tested, 4);
        assertEquals(snapshotContent(commit(reference, 4)), snapshotContent(later));
        assertEquals(retriedContent, snapshotContent(retried));
        writeMany(tested, new Random(~seed), 5);
        tested.restore(later);
        assertEquals(snapshotContent(later), workingContent(tested));
        checkStructure(tested);
    }

    /** An accepted snapshot is the new base: the restore goes back to it, not to the one before. */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void acceptedSnapshotIsTheNewBase(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        writeMany(component, random, 1);
        commit(component, 1);
        writeMany(component, random, 2);
        S accepted = commit(component, 2);
        Object content = snapshotContent(accepted);
        writeMany(component, random, 3);
        component.restore(accepted);
        assertEquals(content, workingContent(component));
        assertEquals(content, snapshotContent(accepted));
        checkStructure(component);
    }

    /** Prepares and accepts a commit, as the storage does when no preparation fails. */
    private S commit(C component, long commitSequence) {
        S prepared = component.prepareCommit(commitSequence);
        component.acceptCommit(prepared);
        return prepared;
    }

    private void writeMany(C component, Random random, long commit) {
        for (int i = 0; i < WRITES; i++) {
            write(component, random, commit);
        }
    }

    private void writeBoth(C tested, C reference, long seed, long commit) {
        writeMany(tested, new Random(seed), commit);
        writeMany(reference, new Random(seed), commit);
    }
}
