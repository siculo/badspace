package badspace.common.partition;

import static badspace.common.partition.PartitionName.partitionName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void rejectsUpperCaseAndOtherCharacters() {
        assertThrows(IllegalArgumentException.class, () -> partitionName("North"));
        for (String name : new String[] {"a/b", "a\\b", "a b", "a:b", "città", "a*"}) {
            assertThrows(IllegalArgumentException.class, () -> partitionName(name), name);
        }
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> partitionName(null));
    }
}
