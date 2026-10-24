package badspace.common.partition;

import static badspace.common.partition.MetadataCondition.absent;
import static badspace.common.partition.MetadataCondition.valueIs;
import static badspace.common.partition.MetadataWrite.delete;
import static badspace.common.partition.MetadataWrite.put;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Checks the keys of the application metadata: the writes and the conditions reject the keys
 * with an unpaired surrogate, which a save in UTF-8 cannot keep, and accept all the others.
 */
class MetadataKeyTest {

    private static final String HIGH = String.valueOf((char) 0xd800);
    private static final String LOW = String.valueOf((char) 0xdc00);
    private static final String ROCKET = "🚀";

    @Test
    void keysWithAnUnpairedSurrogateAreRejected() {
        for (String key : new String[] {HIGH, LOW, "a" + HIGH, HIGH + "a", LOW + HIGH, ROCKET + LOW}) {
            assertThrows(IllegalArgumentException.class, () -> put(1, key, 1), key);
            assertThrows(IllegalArgumentException.class, () -> delete(1, key), key);
            assertThrows(IllegalArgumentException.class, () -> valueIs(1, key, 1), key);
            assertThrows(IllegalArgumentException.class, () -> absent(1, key), key);
        }
    }

    @Test
    void wellFormedKeysAreAccepted() {
        for (String key : new String[] {"", "?", "città", ROCKET, "a" + ROCKET + "b"}) {
            assertEquals(key, put(1, key, 1).key());
            assertEquals(key, delete(1, key).key());
            assertEquals(key, valueIs(1, key, 1).key());
            assertEquals(key, absent(1, key).key());
        }
    }

    @Test
    void nullKeyIsRejected() {
        assertThrows(NullPointerException.class, () -> put(1, null, 1));
        assertThrows(NullPointerException.class, () -> absent(1, null));
    }
}
