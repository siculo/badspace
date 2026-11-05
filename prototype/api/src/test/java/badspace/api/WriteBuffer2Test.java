package badspace.api;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.Entity2.entity;
import static badspace.common.partition.MetadataWrite.delete;
import static badspace.common.partition.MetadataWrite.put;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.EntityWrite2;
import badspace.common.partition.MetadataWrite;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Checks the composition of the writes by entity: the final changes, applied to a base, give
 * the same state as the writes made one by one on the same base (the sequential model).
 */
class WriteBuffer2Test {

    private static final Point2 P1 = point(1, 1);
    private static final Point2 P2 = point(2, 2);
    private static final long[] IDS = {1, 2, 3};
    private static final String[] KEYS = {"a", "b"};
    private static final int RUNS = 5000;
    private static final int MAX_WRITES = 12;

    private final WriteBuffer2 buffer = new WriteBuffer2();

    @Test
    void emptyBufferHasNoChanges() {
        assertTrue(buffer.isEmpty());
        assertEquals(List.of(), buffer.changes());
    }

    @Test
    void lastSetWins() {
        buffer.set(entity(1, 1, 1));
        buffer.set(entity(1, 2, 2));
        assertEquals(List.of(new EntityWrite2(1, false, P2, List.of(), List.of())), buffer.changes());
    }

    @Test
    void setThenRemoveLeavesTheEntityAbsentWithoutMetadata() {
        buffer.set(entity(1, 1, 1));
        buffer.write(put(1, "a", 1));
        buffer.remove(1);
        assertEquals(List.of(new EntityWrite2(1, true, null, List.of(), List.of())), buffer.changes());
    }

    @Test
    void removeThenSetAddsTheEntityAgainWithoutTheOldMetadata() {
        buffer.write(put(1, "a", 1));
        buffer.remove(1);
        buffer.set(entity(1, 2, 2));
        buffer.write(put(1, "b", 2));
        assertEquals(List.of(new EntityWrite2(1, true, P2, List.of(), List.of(put(1, "b", 2)))), buffer.changes());
    }

    @Test
    void onlyTheLastWriteOfAKeyStays() {
        buffer.write(put(1, "a", 1));
        buffer.write(delete(1, "a"));
        buffer.write(put(1, "b", 1));
        buffer.write(put(1, "b", 2));
        assertEquals(List.of(new EntityWrite2(1, false, null, List.of(delete(1, "a"), put(1, "b", 2)), List.of())),
                buffer.changes());
    }

    @Test
    void metadataWriteAfterARemovalIsDropped() {
        buffer.remove(1);
        buffer.write(put(1, "a", 1));
        assertEquals(List.of(new EntityWrite2(1, true, null, List.of(), List.of())), buffer.changes());
    }

    @Test
    void metadataWriteBeforeASetDependsOnTheBaseAndAWriteAfterReplacesIt() {
        buffer.write(put(1, "a", 1));
        buffer.write(put(1, "b", 1));
        buffer.set(entity(1, 1, 1));
        buffer.write(put(1, "b", 2));
        assertEquals(List.of(new EntityWrite2(1, false, P1, List.of(put(1, "a", 1)), List.of(put(1, "b", 2)))),
                buffer.changes());
    }

    @Test
    void entitiesAreIndependentAndInTheOrderOfTheirFirstWrite() {
        buffer.set(entity(2, 1, 1));
        buffer.remove(1);
        buffer.set(entity(2, 2, 2));
        assertEquals(List.of(new EntityWrite2(2, false, P2, List.of(), List.of()),
                new EntityWrite2(1, true, null, List.of(), List.of())), buffer.changes());
    }

    @Test
    void changeRejectsInconsistentFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntityWrite2(1, false, null, List.of(), List.of(put(1, "a", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new EntityWrite2(1, true, P1, List.of(put(1, "a", 1)), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EntityWrite2(1, false, P1, List.of(), List.of(put(2, "a", 1))));
    }

    @Test
    void composedChangesGiveTheSameStateAsTheWritesOneByOne() {
        Random random = new Random(42);
        for (int run = 0; run < RUNS; run++) {
            Map<Long, Model> base = randomBase(random);
            List<Write> writes = randomWrites(random);
            Map<Long, Model> sequential = copy(base);
            WriteBuffer2 composed = new WriteBuffer2();
            for (Write write : writes) {
                write.applyTo(sequential);
                write.addTo(composed);
            }
            assertEquals(sequential, applyChanges(base, composed.changes()), "Base " + base + ", writes " + writes);
        }
    }

    /** The state of one entity in the model: its position and its metadata. */
    private record Model(Point2 position, Map<String, Long> metadata) {

        Model {
            metadata = Map.copyOf(metadata);
        }

        Model with(MetadataWrite write) {
            Map<String, Long> next = new HashMap<>(metadata);
            switch (write) {
                case MetadataWrite.Put put -> next.put(put.key(), put.value());
                case MetadataWrite.Delete delete -> next.remove(delete.key());
            }
            return new Model(position, next);
        }
    }

    /** One write of the writer, applied one by one to the model or added to a buffer. */
    private sealed interface Write {

        void applyTo(Map<Long, Model> state);

        void addTo(WriteBuffer2 buffer);
    }

    private record Set(long entityId, Point2 position) implements Write {

        @Override
        public void applyTo(Map<Long, Model> state) {
            Model current = state.get(entityId);
            state.put(entityId, new Model(position, current == null ? Map.of() : current.metadata()));
        }

        @Override
        public void addTo(WriteBuffer2 buffer) {
            buffer.set(new Entity2(entityId, position));
        }
    }

    private record Remove(long entityId) implements Write {

        @Override
        public void applyTo(Map<Long, Model> state) {
            state.remove(entityId);
        }

        @Override
        public void addTo(WriteBuffer2 buffer) {
            buffer.remove(entityId);
        }
    }

    private record Metadata(MetadataWrite write) implements Write {

        @Override
        public void applyTo(Map<Long, Model> state) {
            state.computeIfPresent(write.entityId(), (id, current) -> current.with(write));
        }

        @Override
        public void addTo(WriteBuffer2 buffer) {
            buffer.write(write);
        }
    }

    /** Applies the final changes to a copy of the base, in the order that EntityWrite2 describes. */
    private static Map<Long, Model> applyChanges(Map<Long, Model> base, List<EntityWrite2> changes) {
        Map<Long, Model> state = copy(base);
        for (EntityWrite2 change : changes) {
            long id = change.entityId();
            boolean inBase = state.containsKey(id);
            if (change.removed()) {
                state.remove(id);
            }
            if (change.position() != null) {
                new Set(id, change.position()).applyTo(state);
            }
            if (inBase) {
                for (MetadataWrite write : change.metadataIfInBase()) {
                    state.computeIfPresent(id, (key, current) -> current.with(write));
                }
            }
            for (MetadataWrite write : change.metadata()) {
                state.put(id, state.get(id).with(write));
            }
        }
        return state;
    }

    private static Map<Long, Model> randomBase(Random random) {
        Map<Long, Model> base = new HashMap<>();
        for (long id : IDS) {
            if (random.nextBoolean()) {
                Map<String, Long> metadata = new HashMap<>();
                for (String key : KEYS) {
                    if (random.nextBoolean()) {
                        metadata.put(key, (long) random.nextInt(3));
                    }
                }
                base.put(id, new Model(point(random.nextInt(3), 0), metadata));
            }
        }
        return base;
    }

    private static List<Write> randomWrites(Random random) {
        List<Write> writes = new ArrayList<>();
        int count = random.nextInt(MAX_WRITES + 1);
        for (int i = 0; i < count; i++) {
            long id = IDS[random.nextInt(IDS.length)];
            String key = KEYS[random.nextInt(KEYS.length)];
            writes.add(switch (random.nextInt(4)) {
                case 0 -> new Set(id, point(random.nextInt(3), 1));
                case 1 -> new Remove(id);
                case 2 -> new Metadata(put(id, key, random.nextInt(3)));
                default -> new Metadata(delete(id, key));
            });
        }
        return writes;
    }

    private static Map<Long, Model> copy(Map<Long, Model> state) {
        return new HashMap<>(state);
    }
}
