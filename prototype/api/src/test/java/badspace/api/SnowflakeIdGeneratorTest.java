package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    private static final long START = SnowflakeIdGenerator.EPOCH_MILLIS + 1_000_000;
    private static final long MAX_DRIFT = 100;

    /** A clock that moves only when the test moves it. Sleeping moves it forward. */
    private final AtomicLong clock = new AtomicLong(START);

    private SnowflakeIdGenerator newGenerator(long generatorId) {
        return new SnowflakeIdGenerator(generatorId, MAX_DRIFT, clock::get, clock::addAndGet);
    }

    @Test
    void idHasTimestampGeneratorAndSequence() {
        SnowflakeIdGenerator generator = newGenerator(5);
        long now = clock.get();
        long id = generator.nextId();
        assertEquals(now, SnowflakeIdGenerator.timestampMillis(id));
        assertEquals(5, SnowflakeIdGenerator.generatorId(id));
        assertEquals(0, SnowflakeIdGenerator.sequence(id));
        assertTrue(id > 0);
    }

    @Test
    void constructorWaitsForTheMaxDrift() {
        List<Long> sleeps = new ArrayList<>();
        new SnowflakeIdGenerator(0, MAX_DRIFT, clock::get, sleeps::add);
        assertEquals(List.of(MAX_DRIFT), sleeps);
    }

    @Test
    void sameMillisecondIncrementsTheSequence() {
        SnowflakeIdGenerator generator = newGenerator(0);
        long first = generator.nextId();
        long second = generator.nextId();
        assertEquals(first + 1, second);
        assertEquals(1, SnowflakeIdGenerator.sequence(second));
    }

    @Test
    void newMillisecondResetsTheSequence() {
        SnowflakeIdGenerator generator = newGenerator(0);
        generator.nextId();
        generator.nextId();
        clock.incrementAndGet();
        long id = generator.nextId();
        assertEquals(0, SnowflakeIdGenerator.sequence(id));
        assertEquals(clock.get(), SnowflakeIdGenerator.timestampMillis(id));
    }

    @Test
    void usedUpSequenceBorrowsTheNextMillisecond() {
        SnowflakeIdGenerator generator = newGenerator(0);
        long now = clock.get();
        long last = 0;
        for (long i = 0; i <= SnowflakeIdGenerator.MAX_SEQUENCE; i++) {
            last = generator.nextId();
        }
        assertEquals(SnowflakeIdGenerator.MAX_SEQUENCE, SnowflakeIdGenerator.sequence(last));

        long borrowed = generator.nextId();
        assertEquals(now + 1, SnowflakeIdGenerator.timestampMillis(borrowed));
        assertEquals(0, SnowflakeIdGenerator.sequence(borrowed));
        assertEquals(now, clock.get());
    }

    @Test
    void clockGoingBackDoesNotRepeatIds() {
        SnowflakeIdGenerator generator = newGenerator(0);
        long before = generator.nextId();
        clock.addAndGet(-MAX_DRIFT);
        long after = generator.nextId();
        assertTrue(after > before);
        assertEquals(SnowflakeIdGenerator.timestampMillis(before), SnowflakeIdGenerator.timestampMillis(after));
    }

    @Test
    void tooMuchDriftFailsAndKeepsTheState() {
        SnowflakeIdGenerator generator = newGenerator(0);
        long before = generator.nextId();
        clock.addAndGet(-MAX_DRIFT - 1);
        assertThrows(IllegalStateException.class, generator::nextId);

        clock.addAndGet(MAX_DRIFT + 1);
        assertEquals(before + 1, generator.nextId());
    }

    @Test
    void borrowingTooMuchFails() {
        SnowflakeIdGenerator generator = newGenerator(0);
        long idsWithinDrift = (MAX_DRIFT + 1) * (SnowflakeIdGenerator.MAX_SEQUENCE + 1);
        for (long i = 0; i < idsWithinDrift; i++) {
            generator.nextId();
        }
        assertThrows(IllegalStateException.class, generator::nextId);
    }

    @Test
    void differentGeneratorsGiveDifferentIdsAtTheSameTime() {
        long a = newGenerator(1).nextId();
        long b = newGenerator(2).nextId();
        assertNotEquals(a, b);
    }

    @Test
    void generatorIdMustFitItsField() {
        assertThrows(IllegalArgumentException.class, () -> newGenerator(-1));
        assertThrows(IllegalArgumentException.class, () -> newGenerator(SnowflakeIdGenerator.MAX_GENERATOR_ID + 1));
        newGenerator(SnowflakeIdGenerator.MAX_GENERATOR_ID);
    }

    @Test
    void clockOutsideTheTimestampRangeFails() {
        clock.set(SnowflakeIdGenerator.EPOCH_MILLIS - 1 - MAX_DRIFT);
        assertThrows(IllegalStateException.class, () -> newGenerator(0));

        clock.set(SnowflakeIdGenerator.EPOCH_MILLIS + SnowflakeIdGenerator.MAX_TIMESTAMP + 1 - MAX_DRIFT);
        assertThrows(IllegalStateException.class, () -> newGenerator(0));
    }

    @Test
    void idsAreUniqueAcrossThreads() throws InterruptedException {
        SnowflakeIdGenerator generator = newGenerator(0);
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                Set<Long> local = new HashSet<>();
                for (int i = 0; i < 10_000; i++) {
                    local.add(generator.nextId());
                }
                ids.addAll(local);
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(40_000, ids.size());
    }
}
