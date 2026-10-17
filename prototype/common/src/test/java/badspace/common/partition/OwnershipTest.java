package badspace.common.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OwnershipTest {

    @Test
    void initialStateHasNoOwnerAtGenerationZero() {
        assertEquals(new Ownership(0, false), Ownership.INITIAL);
    }

    @Test
    void negativeGenerationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Ownership(-1, false));
    }

    @Test
    void tokenNeedsAPartitionAndAGenerationOfAnOwner() {
        assertEquals(1, new WriteToken(new PartitionId(7), 1).generation());
        assertThrows(NullPointerException.class, () -> new WriteToken(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new WriteToken(new PartitionId(7), 0));
    }
}
