package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SnapshotUsesTest {

    @Test
    void snapshotWithoutUsesEndsWhenItLeavesTheWindow() {
        SnapshotUses uses = new SnapshotUses();
        assertFalse(uses.isEnded());
        assertTrue(uses.leaveWindow());
        assertTrue(uses.isEnded());
    }

    @Test
    void snapshotInTheWindowDoesNotEndAtTheLastUse() {
        SnapshotUses uses = new SnapshotUses();
        assertTrue(uses.addUseIfInWindow());
        assertFalse(uses.removeUse());
        assertFalse(uses.isEnded());
    }

    @Test
    void snapshotOutOfTheWindowEndsAtTheLastUse() {
        SnapshotUses uses = new SnapshotUses();
        assertTrue(uses.addUseIfInWindow());
        assertTrue(uses.addUseIfNotEnded());
        assertFalse(uses.leaveWindow());
        assertFalse(uses.removeUse());
        assertFalse(uses.isEnded());
        assertTrue(uses.removeUse());
        assertTrue(uses.isEnded());
    }

    @Test
    void snapshotOutOfTheWindowCannotBeAcquiredButCanBeQueried() {
        SnapshotUses uses = new SnapshotUses();
        assertTrue(uses.addUseIfInWindow());
        uses.leaveWindow();
        assertFalse(uses.addUseIfInWindow());
        assertTrue(uses.addUseIfNotEnded());
    }

    @Test
    void endedSnapshotGetsNoNewUse() {
        SnapshotUses uses = new SnapshotUses();
        uses.leaveWindow();
        assertFalse(uses.addUseIfInWindow());
        assertFalse(uses.addUseIfNotEnded());
        assertTrue(uses.isEnded());
    }

    @Test
    void wrongCallsAreRejected() {
        SnapshotUses uses = new SnapshotUses();
        assertThrows(IllegalStateException.class, uses::removeUse);
        uses.addUseIfInWindow();
        uses.leaveWindow();
        assertThrows(IllegalStateException.class, uses::leaveWindow);
    }
}
