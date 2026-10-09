package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compares the table with a HashMap after random writes, and checks that the
 * copies do not change. The IDs come from a small set, with negative and
 * extreme values, so that the table grows, fills its places again after the
 * removals and has long runs of full places.
 */
class SlotTableTest {

    private static final int STEPS = 5000;
    private static final int KEPT_COPIES = 4;
    private static final long[] EXTREME_IDS = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE};

    private record KeptCopy(SlotTable table, Map<Long, Integer> model) {
    }

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 10);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void randomWritesGiveTheSameResultsAsAHashMap(long seed) {
        Random random = new Random(seed);
        List<Long> ids = new ArrayList<>();
        for (long id : EXTREME_IDS) {
            ids.add(id);
        }
        for (int i = 0; i < 300; i++) {
            ids.add(random.nextLong(-1000, 1000));
        }
        SlotTable table = new SlotTable();
        Map<Long, Integer> model = new HashMap<>();
        List<KeptCopy> kept = new ArrayList<>();
        for (int step = 0; step < STEPS; step++) {
            // Long phases of insertions and of removals make the table grow and empty again.
            boolean removing = (step / 500) % 2 == 1;
            long id = ids.get(random.nextInt(ids.size()));
            String where = "seed " + seed + ", step " + step + ", id " + id;
            if (random.nextInt(4) < (removing ? 3 : 1)) {
                Integer expected = model.remove(id);
                assertEquals(expected == null ? SlotTable.NONE : expected, table.remove(id), where + ": remove");
            } else {
                int slot = random.nextInt(1000);
                table.put(id, slot);
                model.put(id, slot);
            }
            assertEquals(model.size(), table.size(), where + ": size");
            if (random.nextInt(100) == 0) {
                kept.add(new KeptCopy(table.copy(), Map.copyOf(model)));
                if (kept.size() > KEPT_COPIES) {
                    kept.removeFirst();
                }
            }
            if (step % 100 == 99) {
                check(table, model, ids, where);
                for (KeptCopy c : kept) {
                    check(c.table(), c.model(), ids, where + ", kept copy");
                }
            }
        }
    }

    @Test
    void emptyTableHasNothing() {
        SlotTable table = new SlotTable();
        assertEquals(SlotTable.NONE, table.get(0));
        assertEquals(SlotTable.NONE, table.remove(0));
        assertFalse(table.contains(Long.MIN_VALUE));
        assertEquals(0, table.size());
    }

    @Test
    void copyDoesNotSeeLaterWrites() {
        SlotTable table = new SlotTable();
        table.put(10, 0);
        table.put(20, 1);
        SlotTable copy = table.copy();
        table.put(10, 5);
        table.remove(20);
        table.put(30, 2);
        assertEquals(0, copy.get(10));
        assertEquals(1, copy.get(20));
        assertFalse(copy.contains(30));
        assertEquals(2, copy.size());
        assertEquals(5, table.get(10));
        assertTrue(table.contains(30));
        assertFalse(table.contains(20));
    }

    @Test
    void negativeSlotIsRejected() {
        SlotTable table = new SlotTable();
        assertThrows(IllegalArgumentException.class, () -> table.put(10, SlotTable.NONE));
        assertEquals(0, table.size());
    }

    private static void check(SlotTable table, Map<Long, Integer> model, List<Long> ids, String where) {
        assertEquals(model.size(), table.size(), where + ": size");
        for (long id : ids) {
            Integer expected = model.get(id);
            assertEquals(expected == null ? SlotTable.NONE : expected, table.get(id), where + ": get " + id);
            assertEquals(expected != null, table.contains(id), where + ": contains " + id);
        }
    }
}
