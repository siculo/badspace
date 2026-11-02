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
 * The commit sequences start at 1 and grow by one at each commit, as in the partition storage.
 *
 * @param <C> the type of the structure
 * @param <S> the type of its snapshots
 */
public abstract class TransactionalComponentContract<C extends TransactionalComponent<S>, S> {

    private static final int WRITES = 40;

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
        S first = component.prepareCommit(1);
        Object content = snapshotContent(first);
        assertEquals(workingContent(component), content);
        writeMany(component, random, 2);
        component.prepareCommit(2);
        writeMany(component, random, 3);
        assertEquals(content, snapshotContent(first));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void restoreBringsBackTheContentOfTheSnapshot(long seed) {
        Random random = new Random(seed);
        C component = newComponent();
        writeMany(component, random, 1);
        S confirmed = component.prepareCommit(1);
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
        S confirmed = component.prepareCommit(1);
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
        S confirmed = component.prepareCommit(1);
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
        S withoutWrites = component.prepareCommit(1);
        assertEquals(snapshotContent(emptySnapshot()), snapshotContent(withoutWrites));
        writeMany(component, random, 2);
        S first = component.prepareCommit(2);
        S second = component.prepareCommit(3);
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
        S confirmed = tested.prepareCommit(1);
        reference.prepareCommit(1);
        Object content = snapshotContent(confirmed);
        writeMany(tested, new Random(~seed), 2);
        tested.restore(confirmed);
        for (long commit = 2; commit <= 4; commit++) {
            writeBoth(tested, reference, seed * 31 + commit, commit);
            checkStructure(tested);
            assertEquals(workingContent(reference), workingContent(tested), "working state before commit " + commit);
            assertEquals(snapshotContent(reference.prepareCommit(commit)), snapshotContent(tested.prepareCommit(commit)),
                    "commit " + commit);
        }
        assertEquals(content, snapshotContent(confirmed));
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
