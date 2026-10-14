package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionUsesTest {

    @Test
    void versionWithoutUsesEndsWhenItLeavesTheWindow() {
        VersionUses uses = new VersionUses();
        assertFalse(uses.isEnded());
        assertTrue(uses.leaveWindow());
        assertTrue(uses.isEnded());
    }

    @Test
    void versionInTheWindowDoesNotEndAtTheLastUse() {
        VersionUses uses = new VersionUses();
        assertTrue(uses.addUseIfInWindow());
        assertFalse(uses.removeUse());
        assertFalse(uses.isEnded());
    }

    @Test
    void versionOutOfTheWindowEndsAtTheLastUse() {
        VersionUses uses = new VersionUses();
        assertTrue(uses.addUseIfInWindow());
        assertTrue(uses.addUseIfNotEnded());
        assertFalse(uses.leaveWindow());
        assertFalse(uses.removeUse());
        assertFalse(uses.isEnded());
        assertTrue(uses.removeUse());
        assertTrue(uses.isEnded());
    }

    @Test
    void versionOutOfTheWindowCannotBeAcquiredButCanBeQueried() {
        VersionUses uses = new VersionUses();
        assertTrue(uses.addUseIfInWindow());
        uses.leaveWindow();
        assertFalse(uses.addUseIfInWindow());
        assertTrue(uses.addUseIfNotEnded());
    }

    @Test
    void endedVersionGetsNoNewUse() {
        VersionUses uses = new VersionUses();
        uses.leaveWindow();
        assertFalse(uses.addUseIfInWindow());
        assertFalse(uses.addUseIfNotEnded());
        assertTrue(uses.isEnded());
    }

    @Test
    void wrongCallsAreRejected() {
        VersionUses uses = new VersionUses();
        assertThrows(IllegalStateException.class, uses::removeUse);
        uses.addUseIfInWindow();
        uses.leaveWindow();
        assertThrows(IllegalStateException.class, uses::leaveWindow);
    }
}
