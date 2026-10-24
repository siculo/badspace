package badspace.common.partition;

import static badspace.common.partition.PartitionName.partitionName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Checks the rules of a partition name, which becomes the name of a directory. */
class PartitionNameTest {

    @Test
    void acceptsLowerCaseLettersDigitsAndThreeSigns() {
        assertEquals("north-2_b.v1", partitionName("north-2_b.v1").value());
        assertEquals("106401747404062720", partitionName("106401747404062720").value());
        assertEquals("a".repeat(PartitionName.MAX_LENGTH), partitionName("a".repeat(PartitionName.MAX_LENGTH)).value());
    }

    @Test
    void rejectsAnEmptyOrTooLongName() {
        assertThrows(IllegalArgumentException.class, () -> partitionName(""));
        assertThrows(IllegalArgumentException.class, () -> partitionName("a".repeat(PartitionName.MAX_LENGTH + 1)));
    }

    @Test
    void rejectsTheNamesOfTheCurrentAndParentDirectory() {
        assertThrows(IllegalArgumentException.class, () -> partitionName("."));
        assertThrows(IllegalArgumentException.class, () -> partitionName(".."));
    }

    @Test
    void rejectsANameThatEndsWithADot() {
        // Windows removes the final dots: "arena." would be the directory of "arena".
        for (String name : new String[] {"arena.", "arena..", "...", "a.b."}) {
            assertThrows(IllegalArgumentException.class, () -> partitionName(name), name);
        }
        assertEquals(".arena", partitionName(".arena").value());
        assertEquals("a..b", partitionName("a..b").value());
    }

    @Test
    void rejectsTheDeviceNamesOfWindowsAlsoWithAnExtension() {
        for (String name : new String[] {"con", "prn", "aux", "nul", "com0", "com9", "lpt1", "nul.save", "con.a.b"}) {
            assertThrows(IllegalArgumentException.class, () -> partitionName(name), name);
        }
        for (String name : new String[] {"console", "con-1", "com10", "lpt", "a.con", "nul_"}) {
            assertEquals(name, partitionName(name).value());
        }
    }

    @Test
    void rejectsUpperCaseAndOtherCharacters() {
        assertThrows(IllegalArgumentException.class, () -> partitionName("North"));
        for (String name : new String[] {"a/b", "a\\b", "a b", "a:b", "città", "a*"}) {
            assertThrows(IllegalArgumentException.class, () -> partitionName(name), name);
        }
    }

    @Test
    void isValidFollowsTheRulesOfTheConstructor() {
        assertTrue(PartitionName.isValid("north-2_b.v1"));
        for (String name : new String[] {"", "a".repeat(PartitionName.MAX_LENGTH + 1), ".", "..", "North", "a/b",
                "arena.", "nul", "com1.x"}) {
            assertFalse(PartitionName.isValid(name), name);
        }
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> partitionName(null));
    }
}
