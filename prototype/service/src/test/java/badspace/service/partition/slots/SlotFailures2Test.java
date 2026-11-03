package badspace.service.partition.slots;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Point2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks that an allocation that fails, as if the memory ran out, leaves the 2D slots
 * consistent: a failed growth keeps the old capacity, so the restore and the next growth work,
 * and a failed preparation gives back the buffer it took, as a buffer that holds no commit.
 */
class SlotFailures2Test {

    /** The capacity of new slots. */
    private static final int CAPACITY = 16;

    private final FailingSlotArrays arrays = new FailingSlotArrays();
    private final FailingBufferDeque pooled = new FailingBufferDeque();
    private MutableSlots2 slots;

    @BeforeEach
    void setUp() {
        slots = new MutableSlots2(new CoordinateBufferPool(pooled), arrays);
    }

    @Test
    void failedGrowthKeepsTheCapacityAndTheRestoreWithoutWritesWorks() {
        SlotSnapshot2 confirmed = commitFull(1);
        OutOfMemoryError cause = new OutOfMemoryError("Memory failure for the test");
        arrays.doublesFailure = cause;
        assertSame(cause, assertThrows(OutOfMemoryError.class, () -> slots.ensureCapacity(2 * CAPACITY)));
        slots.restore(confirmed);
        checkGrowthAfterTheFailure(2);
    }

    @Test
    void failedGrowthKeepsTheCapacityAndTheRestoreAfterWritesWorks() {
        SlotSnapshot2 confirmed = commitFull(1);
        slots.write(0, new Point2(-1, -1), 2);
        arrays.longsFailure = new OutOfMemoryError("Memory failure for the test");
        assertThrows(OutOfMemoryError.class, () -> slots.ensureCapacity(2 * CAPACITY));
        slots.restore(confirmed);
        assertEquals(new Point2(0, 0), slots.positionAt(0));
        checkGrowthAfterTheFailure(2);
    }

    /**
     * The preparation took a new buffer and copied into it the moved entity of the first page;
     * then the copy of the IDs failed. The buffer goes back to the pool holding no commit: after
     * the restore, the next commit, which moves only an entity of the second page, takes it
     * again and copies also the first page, which no longer has the move.
     */
    @Test
    void failedPreparationGivesBackTheBufferItTook() {
        for (int i = 0; i < 2 * MutableSlots2.PAGE_SLOTS; i++) {
            slots.insert(i, new Point2(i, 0), 1);
        }
        SlotSnapshot2 confirmed = slots.prepareCommit(1);
        slots.acceptCommit(confirmed);
        slots.write(0, new Point2(-1, -1), 2);
        slots.remove(1, 2);
        OutOfMemoryError cause = new OutOfMemoryError("Memory failure for the test");
        arrays.longsFailure = cause;
        assertSame(cause, assertThrows(OutOfMemoryError.class, () -> slots.prepareCommit(2)));
        assertEquals(1, pooled.size());
        CoordinateBuffer given = pooled.peekFirst();

        slots.restore(confirmed);
        int last = 2 * MutableSlots2.PAGE_SLOTS - 1;
        slots.write(last, new Point2(-2, -2), 3);
        SlotSnapshot2 next = slots.prepareCommit(3);
        slots.acceptCommit(next);
        assertSame(given, next.coordinateBuffer());
        assertEquals(new Point2(0, 0), next.positionAt(0));
        assertEquals(new Point2(1, 0), next.positionAt(1));
        assertEquals(new Point2(-2, -2), next.positionAt(last));
    }

    @Test
    void failedPreparationWithoutChangedCoordinatesKeepsTheBufferOfTheLastSnapshot() {
        slots.insert(1, new Point2(1, 1), 1);
        SlotSnapshot2 confirmed = slots.prepareCommit(1);
        slots.acceptCommit(confirmed);
        slots.remove(1, 2);
        arrays.longsFailure = new OutOfMemoryError("Memory failure for the test");
        assertThrows(OutOfMemoryError.class, () -> slots.prepareCommit(2));
        // The shared buffer stays with the confirmed snapshot, and the next commit shares it too.
        assertEquals(0, pooled.size());
        SlotSnapshot2 next = slots.prepareCommit(3);
        assertSame(confirmed.coordinateBuffer(), next.coordinateBuffer());
        assertEquals(0, next.size());
    }

    @Test
    void failureOfTheGiveBackIsSuppressedInTheFailureOfThePreparation() {
        prepareWithNewIdsAndCoordinates();
        OutOfMemoryError cause = new OutOfMemoryError("Memory failure for the test");
        OutOfMemoryError giveBackCause = new OutOfMemoryError("Give back failure for the test");
        arrays.longsFailure = cause;
        pooled.addFailure = giveBackCause;
        assertSame(cause, assertThrows(OutOfMemoryError.class, () -> slots.prepareCommit(2)));
        assertArrayEquals(new Throwable[] {giveBackCause}, cause.getSuppressed());
        assertEquals(0, pooled.size());
    }

    /** The JVM can throw the same instance of OutOfMemoryError again: it cannot suppress itself. */
    @Test
    void sameFailureInTheGiveBackKeepsTheFailureOfThePreparation() {
        prepareWithNewIdsAndCoordinates();
        OutOfMemoryError cause = new OutOfMemoryError("Memory failure for the test");
        arrays.longsFailure = cause;
        pooled.addFailure = cause;
        assertSame(cause, assertThrows(OutOfMemoryError.class, () -> slots.prepareCommit(2)));
        assertEquals(0, cause.getSuppressed().length);
    }

    /** Commits one entity and inserts another: the next preparation takes a buffer and copies the IDs. */
    private void prepareWithNewIdsAndCoordinates() {
        slots.insert(1, new Point2(1, 1), 1);
        slots.acceptCommit(slots.prepareCommit(1));
        slots.insert(2, new Point2(2, 2), 2);
    }

    /** Fills the slots up to their capacity and commits them. */
    private SlotSnapshot2 commitFull(long commit) {
        for (int i = 0; i < CAPACITY; i++) {
            slots.insert(i, new Point2(i, 0), commit);
        }
        SlotSnapshot2 snapshot = slots.prepareCommit(commit);
        slots.acceptCommit(snapshot);
        return snapshot;
    }

    /** Inserts after the old capacity, commits, and checks all the entities. */
    private void checkGrowthAfterTheFailure(long commit) {
        slots.ensureCapacity(CAPACITY + 1);
        slots.insert(CAPACITY, new Point2(CAPACITY, 0), commit);
        SlotSnapshot2 next = slots.prepareCommit(commit);
        slots.acceptCommit(next);
        assertEquals(CAPACITY + 1, next.size());
        for (int i = 0; i <= CAPACITY; i++) {
            assertEquals(i, next.idAt(i));
            assertEquals(new Point2(i, 0), next.positionAt(i));
        }
    }
}
