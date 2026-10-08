package badspace.service.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compares the trie with a HashMap after random writes, and checks that the
 * views of the commits do not change. Keys with few hash codes make many
 * keys with the same hash, so the lists at the bottom of the trie are tested too.
 */
class HashTrieTest {

    private static final int STEPS = 2000;
    private static final int MAX_KEY = 200;
    private static final int KEPT_VIEWS = 4;

    /** A key with a chosen hash code. */
    private record Key(int id, int hash) {

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.id == id;
        }
    }

    private record KeptView(HashTrie.View<Key, Integer> view, Map<Key, Integer> model) {
    }

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 10);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void randomWritesWithDifferentHashes(long seed) {
        runRandomWrites(seed, Integer.MAX_VALUE);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void randomWritesWithManyEqualHashes(long seed) {
        runRandomWrites(seed, 8);
    }

    @Test
    void emptyTrieHasNothing() {
        HashTrie<Key, Integer> trie = new HashTrie<>();
        assertNull(trie.get(new Key(1, 1)));
        trie.remove(new Key(1, 1));
        assertEquals(0, trie.size());
        assertEquals(List.of(), valuesOf(trie.view()));
    }

    @Test
    void viewOfACommitDoesNotSeeLaterWrites() {
        HashTrie<Key, Integer> trie = new HashTrie<>();
        Key a = new Key(1, 1);
        Key b = new Key(2, 1);
        trie.put(a, 10);
        trie.put(b, 20);
        HashTrie.View<Key, Integer> view = trie.commit(1);
        trie.put(a, 11);
        trie.remove(b);
        trie.put(new Key(3, 3), 30);
        assertEquals(10, view.get(a));
        assertEquals(20, view.get(b));
        assertEquals(2, view.size());
        assertEquals(11, trie.get(a));
        assertNull(trie.get(b));
        assertEquals(2, trie.size());
    }

    private static void runRandomWrites(long seed, int hashes) {
        Random random = new Random(seed);
        HashTrie<Key, Integer> trie = new HashTrie<>();
        Map<Key, Integer> model = new HashMap<>();
        List<KeptView> kept = new ArrayList<>();
        for (int step = 0; step < STEPS; step++) {
            int id = random.nextInt(MAX_KEY);
            Key key = new Key(id, id % hashes);
            if (random.nextInt(3) == 0) {
                trie.remove(key);
                model.remove(key);
            } else {
                int value = random.nextInt();
                trie.put(key, value);
                model.put(key, value);
            }
            String where = "seed " + seed + ", step " + step;
            assertEquals(model.size(), trie.size(), where + ": size");
            assertEquals(model.get(key), trie.get(key), where + ": get " + id);
            if (random.nextInt(50) == 0) {
                kept.add(new KeptView(trie.commit(step + 1), Map.copyOf(model)));
                if (kept.size() > KEPT_VIEWS) {
                    kept.removeFirst();
                }
            }
            if (step % 100 == 99) {
                checkView(trie.view(), model, hashes, where);
                for (KeptView v : kept) {
                    checkView(v.view(), v.model(), hashes, where + ", kept view");
                }
            }
        }
    }

    private static void checkView(HashTrie.View<Key, Integer> view, Map<Key, Integer> model, int hashes, String where) {
        assertEquals(model.size(), view.size(), where + ": size");
        for (int id = 0; id < MAX_KEY; id++) {
            Key key = new Key(id, id % hashes);
            assertEquals(model.get(key), view.get(key), where + ": get " + id);
        }
        List<Integer> values = valuesOf(view);
        assertEquals(model.size(), values.size(), where + ": number of values");
        assertEquals(new HashSet<>(model.values()), Set.copyOf(values), where + ": values");
    }

    private static List<Integer> valuesOf(HashTrie.View<Key, Integer> view) {
        List<Integer> values = new ArrayList<>();
        view.forEachValue(values::add);
        return values;
    }
}
