package badspace.service.partition;

import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.common.partition.Entity3.entity;
import static badspace.common.partition.MetadataCondition.absent;
import static badspace.common.partition.MetadataCondition.valueIs;
import static badspace.common.partition.MetadataWrite.delete;
import static badspace.common.partition.MetadataWrite.put;
import static badspace.service.partition.Names.nameOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.partition.Acquisition;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.ConditionalWrite3;
import badspace.common.partition.Entity3;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataCondition;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionId;
import badspace.common.partition.WriteToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the application metadata of the entities on the node: the writes, the reads through
 * the snapshots of the commits, the removal of the entities and the discarded writes.
 */
class PartitionMetadata3Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int THREADS = 8;

    private LocalPartitionNode3 node;
    private WriteToken token;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode3();
        node.createPartition(P1, nameOf(P1), IndexConfig.uniformGrid(4));
        token = node.claim(P1, 0).orElseThrow();
        node.insertAll(token, List.of(entity(1, 1, 1, 1), entity(2, 2, 2, 2), entity(3, 3, 3, 3)));
    }

    @Test
    void committedMetadataAreReadThroughTheSnapshot() {
        node.updateMetadataAll(token, List.of(put(1, "version", 7), put(1, "owner", -3), put(2, "version", 1)));
        assertEquals(List.of(metadata(1, Map.of("version", 7L, "owner", -3L)), metadata(2, Map.of("version", 1L))),
                node.getMetadataAll(publish(), new long[] {1, 2}));
    }

    @Test
    void entityWithoutMetadataHasAnEmptyMapAndIdsNotInTheSnapshotAreSkipped() {
        node.updateMetadataAll(token, List.of(put(2, "version", 1)));
        assertEquals(List.of(metadata(3, Map.of()), metadata(2, Map.of("version", 1L))),
                node.getMetadataAll(publish(), new long[] {3, 99, 2}));
    }

    @Test
    void lastWriteOfAKeyWinsAndDeleteRemovesTheKey() {
        node.updateMetadataAll(token, List.of(put(1, "a", 1), put(1, "b", 2), put(1, "a", 3), delete(1, "b"),
                delete(1, "missing"), put(2, "a", 1), delete(2, "a")));
        assertEquals(List.of(metadata(1, Map.of("a", 3L)), metadata(2, Map.of())),
                node.getMetadataAll(publish(), new long[] {1, 2}));
    }

    @Test
    void snapshotOfACommitDoesNotSeeLaterMetadataWrites() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        Acquisition first = publish();
        node.updateMetadataAll(token, List.of(put(1, "version", 2), put(2, "version", 1)));
        Acquisition second = publish();
        assertEquals(List.of(metadata(1, Map.of("version", 1L)), metadata(2, Map.of())),
                node.getMetadataAll(first, new long[] {1, 2}));
        assertEquals(List.of(metadata(1, Map.of("version", 2L)), metadata(2, Map.of("version", 1L))),
                node.getMetadataAll(second, new long[] {1, 2}));
    }

    @Test
    void metadataWritesNotCommittedAreNotInTheSnapshot() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        node.commit(token);
        node.updateMetadataAll(token, List.of(put(1, "version", 2)));
        node.setTick(1);
        assertEquals(List.of(metadata(1, Map.of("version", 1L))),
                node.getMetadataAll(node.acquireLatest(P1, defaultTimeout()), new long[] {1}));
    }

    @Test
    void metadataAndPositionsChangeInTheSameCommit() {
        Acquisition before = publish();
        node.updateAll(token, List.of(entity(1, 5, 5, 5)));
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        Acquisition after = publish();
        assertEquals(List.of(entity(1, 1, 1, 1)), node.getAll(before, new long[] {1}));
        assertEquals(List.of(metadata(1, Map.of())), node.getMetadataAll(before, new long[] {1}));
        assertEquals(List.of(entity(1, 5, 5, 5)), node.getAll(after, new long[] {1}));
        assertEquals(List.of(metadata(1, Map.of("version", 1L))), node.getMetadataAll(after, new long[] {1}));
    }

    @Test
    void writeOnAnEntityNotInThePartitionFailsAndChangesNothing() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        assertThrows(NoSuchElementException.class,
                () -> node.updateMetadataAll(token, List.of(put(1, "version", 2), put(99, "version", 1))));
        assertEquals(List.of(metadata(1, Map.of("version", 1L))), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void entityInsertedAndNotCommittedCanHaveMetadata() {
        node.insertAll(token, List.of(entity(4, 4, 4, 4)));
        node.updateMetadataAll(token, List.of(put(4, "expiry", 30)));
        assertEquals(List.of(metadata(4, Map.of("expiry", 30L))), node.getMetadataAll(publish(), new long[] {4}));
    }

    @Test
    void removalOfAnEntityDeletesItsMetadata() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1), put(2, "version", 1)));
        Acquisition before = publish();
        node.removeAll(token, new long[] {1});
        node.insertAll(token, List.of(entity(1, 1, 1, 1)));
        Acquisition after = publish();
        assertEquals(List.of(metadata(1, Map.of()), metadata(2, Map.of("version", 1L))),
                node.getMetadataAll(after, new long[] {1, 2}));
        assertEquals(List.of(metadata(1, Map.of("version", 1L))), node.getMetadataAll(before, new long[] {1}));
    }

    @Test
    void claimDiscardsTheMetadataWritesNotCommittedAndKeepsTheCommits() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        node.commit(token);
        node.updateMetadataAll(token, List.of(put(1, "version", 2), put(2, "version", 1)));
        token = node.claim(P1, token.generation()).orElseThrow();
        assertEquals(List.of(metadata(1, Map.of("version", 1L)), metadata(2, Map.of())),
                node.getMetadataAll(publish(), new long[] {1, 2}));
    }

    @Test
    void discardBeforeTheFirstCommitLeavesNoMetadata() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        token = node.claim(P1, token.generation()).orElseThrow();
        node.insertAll(token, List.of(entity(1, 1, 1, 1)));
        node.updateMetadataAll(token, List.of(put(1, "owner", 2)));
        assertEquals(List.of(metadata(1, Map.of("owner", 2L))), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void metadataWriteWithAnOldTokenIsRejected() {
        node.commit(token);
        WriteToken old = token;
        token = node.claim(P1, old.generation()).orElseThrow();
        assertThrows(OwnershipChangedException.class,
                () -> node.updateMetadataAll(old, List.of(put(1, "version", 1))));
        assertEquals(List.of(metadata(1, Map.of())), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void metadataWriteAfterTheRemovalIsRejected() {
        node.dropPartition(token);
        assertThrows(IllegalStateException.class, () -> node.updateMetadataAll(token, List.of(put(1, "version", 1))));
    }

    @Test
    void conditionalWriteAppliesPositionsAndMetadataWhenAllConditionsAreTrue() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        List<MetadataCondition> failed = node.updateIf(token, write(List.of(valueIs(1, "version", 1), absent(2, "reserved")),
                List.of(entity(1, 5, 5, 5)), List.of(put(1, "version", 2), put(2, "reserved", 1))));
        assertEquals(List.of(), failed);
        Acquisition acquisition = publish();
        assertEquals(List.of(entity(1, 5, 5, 5)), node.getAll(acquisition, new long[] {1}));
        assertEquals(List.of(metadata(1, Map.of("version", 2L)), metadata(2, Map.of("reserved", 1L))),
                node.getMetadataAll(acquisition, new long[] {1, 2}));
    }

    @Test
    void falseConditionsAreReturnedInTheirOrderAndNothingChanges() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1), put(2, "reserved", 1)));
        MetadataCondition wrongValue = valueIs(1, "version", 2);
        MetadataCondition missingKey = valueIs(3, "version", 1);
        MetadataCondition notAbsent = absent(2, "reserved");
        List<MetadataCondition> failed = node.updateIf(token, write(
                List.of(wrongValue, absent(3, "reserved"), missingKey, notAbsent),
                List.of(entity(1, 5, 5, 5)), List.of(put(1, "version", 3), delete(2, "reserved"))));
        assertEquals(List.of(wrongValue, missingKey, notAbsent), failed);
        Acquisition acquisition = publish();
        assertEquals(List.of(entity(1, 1, 1, 1)), node.getAll(acquisition, new long[] {1}));
        assertEquals(List.of(metadata(1, Map.of("version", 1L)), metadata(2, Map.of("reserved", 1L))),
                node.getMetadataAll(acquisition, new long[] {1, 2}));
    }

    @Test
    void conditionsSeeTheWritesNotCommitted() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        node.commit(token);
        node.updateMetadataAll(token, List.of(put(1, "version", 2)));
        assertEquals(List.of(valueIs(1, "version", 1)),
                node.updateIf(token, write(List.of(valueIs(1, "version", 1)), List.of(), List.of(put(1, "version", 9)))));
        assertEquals(List.of(),
                node.updateIf(token, write(List.of(valueIs(1, "version", 2)), List.of(), List.of(put(1, "version", 3)))));
        assertEquals(List.of(metadata(1, Map.of("version", 3L))), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void conditionalWriteWithoutConditionsIsApplied() {
        assertEquals(List.of(), node.updateIf(token, write(List.of(), List.of(entity(3, 7, 7, 7)), List.of(put(3, "a", 1)))));
        Acquisition acquisition = publish();
        assertEquals(List.of(entity(3, 7, 7, 7)), node.getAll(acquisition, new long[] {3}));
        assertEquals(List.of(metadata(3, Map.of("a", 1L))), node.getMetadataAll(acquisition, new long[] {3}));
    }

    @Test
    void errorsInTheConditionalWriteFailBeforeTheConditionsAndChangeNothing() {
        node.updateMetadataAll(token, List.of(put(1, "version", 1)));
        assertThrows(NoSuchElementException.class, () -> node.updateIf(token,
                write(List.of(valueIs(99, "version", 1)), List.of(), List.of(put(1, "version", 2)))));
        assertThrows(NoSuchElementException.class, () -> node.updateIf(token,
                write(List.of(valueIs(1, "version", 1)), List.of(), List.of(put(1, "version", 2), put(99, "a", 1)))));
        assertThrows(NoSuchElementException.class, () -> node.updateIf(token,
                write(List.of(valueIs(1, "version", 5)), List.of(entity(99, 1, 1, 1)), List.of())));
        assertThrows(IllegalArgumentException.class, () -> node.updateIf(token,
                write(List.of(valueIs(1, "version", 1)), List.of(entity(1, 5, 5, 5), entity(2, 0x1p61, 0, 0)), List.of(put(1, "version", 2)))));
        Acquisition acquisition = publish();
        assertEquals(List.of(entity(1, 1, 1, 1), entity(2, 2, 2, 2)), node.getAll(acquisition, new long[] {1, 2}));
        assertEquals(List.of(metadata(1, Map.of("version", 1L))), node.getMetadataAll(acquisition, new long[] {1}));
    }

    @Test
    void conditionalWriteWithAnOldTokenIsRejected() {
        node.commit(token);
        WriteToken old = token;
        token = node.claim(P1, old.generation()).orElseThrow();
        assertThrows(OwnershipChangedException.class,
                () -> node.updateIf(old, write(List.of(absent(1, "version")), List.of(), List.of(put(1, "version", 1)))));
        assertEquals(List.of(metadata(1, Map.of())), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void ofManyCompareAndSetsFromTheSameValueOnlyOneWins() throws Exception {
        node.updateMetadataAll(token, List.of(put(1, "version", 0)));
        CyclicBarrier start = new CyclicBarrier(THREADS);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long value = i + 1;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    if (node.updateIf(token, write(List.of(valueIs(1, "version", 0)), List.of(),
                            List.of(put(1, "version", value)))).isEmpty()) {
                        winners.incrementAndGet();
                    }
                    attempts.incrementAndGet();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    start.reset();
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join(TIMEOUT.toMillis());
            assertTrue(!thread.isAlive(), "Worker did not end");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(THREADS, attempts.get());
        assertEquals(1, winners.get());
        long version = node.getMetadataAll(publish(), new long[] {1}).getFirst().values().get("version");
        assertTrue(version >= 1 && version <= THREADS, "Version of the winner: " + version);
    }

    private static ConditionalWrite3 write(List<MetadataCondition> conditions, List<Entity3> positions,
            List<MetadataWrite> metadata) {
        return new ConditionalWrite3(conditions, positions, metadata);
    }

    private static ApplicationMetadata metadata(long entityId, Map<String, Long> values) {
        return new ApplicationMetadata(entityId, values);
    }

    /** Commits the writes, moves the tick and acquires the snapshot of the commit. */
    private Acquisition publish() {
        node.commit(token);
        node.setTick(node.tick() + 1);
        return node.acquireLatest(P1, defaultTimeout());
    }
}
